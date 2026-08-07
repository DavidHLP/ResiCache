package io.github.davidhlp.spring.cache.redis.protection.refresh;

import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.model.*;


import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.cache.CachedValue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 提前过期处理器，防止缓存雪崩
 *
 * <p>职责：
 * <ul>
 *   <li>检查缓存是否需要提前过期</li>
 *   <li>同步模式：返回 miss 触发刷新</li>
 *   <li>异步模式：安排后台刷新，缩短 TTL</li>
 * </ul>
 *
 * <p>设计说明：
 * <ul>
 *   <li>独立 Handler，直接检查缓存值并做出决策</li>
 *   <li>GET 操作时，先获取缓存值，判断是否需要提前过期</li>
 *   <li>如果需要同步刷新，返回 skipAll，ActualCacheHandler 检查标记后返回 miss</li>
 * </ul>
 */
@Slf4j
@Component
@HandlerPriority(HandlerOrder.EARLY_EXPIRATION)
public class EarlyExpirationHandler extends AbstractCacheHandler {

    private static final long REFRESH_GRACE_PERIOD_SECONDS = 5;

    /**
     * fast-path TTL 阈值:当 Redis 报告的剩余 TTL 仍大于此值时,跳过本 handler 的
     * 全部逻辑(包括 value GET),让 ActualCacheHandler 走原生一次 GET 完成 hit 处理 —
     * 把热路径从"early handler GET + actual GET"两次 RTT 降为"actual GET"一次 RTT。
     *
     * <p>选 60s 是因为典型缓存 TTL 是 30s~10min,refresh window 通常在剩余最后
     * 5-30%(<3min);绝对阈值 60s 落在"不会立即需要刷新"的合理边界。比百分比策略
     * 更简单也更便宜 —— 不需要先 GET CachedValue 拿 totalTtl。
     *
     * <p>边界:剩余 TTL <= 60s 时走 GET + policy check,行为对调用方无变化。
     */
    static final long FAST_PATH_TTL_SECONDS = 60L;

    private final EarlyExpirationPolicy earlyExpirationPolicy;
    private final ThreadPoolEarlyExpirationExecutor earlyExpirationExecutor;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheStatisticsCollector statistics;
    private final ValueOperations<String, Object> valueOperations;

    public EarlyExpirationHandler(EarlyExpirationPolicy earlyExpirationPolicy,
                                  ThreadPoolEarlyExpirationExecutor earlyExpirationExecutor,
                                  RedisTemplate<String, Object> redisTemplate,
                                  CacheStatisticsCollector statistics,
                                  ValueOperations<String, Object> valueOperations) {
        this.earlyExpirationPolicy = earlyExpirationPolicy;
        this.earlyExpirationExecutor = earlyExpirationExecutor;
        this.redisTemplate = redisTemplate;
        this.statistics = statistics;
        this.valueOperations = valueOperations;
    }

    /**
     * 语义 counter 元数据声明:同步提前过期触发事件计数。
     */
    @Override
    protected CounterMetadata semanticCounter() {
        return new CounterMetadata(
                "resicache.handler.early-refresh.triggered",
                "Early refresh triggered (sync=true early expiration path, ActualCacheHandler skipped)");
    }

    @Override
    protected boolean shouldHandle(CacheContext context) {
        // 仅 GET 操作且启用了提前过期
        return context.getOperation() == CacheOperation.GET
               && context.getCacheOperation() != null
               && context.getCacheOperation().isEnableEarlyExpiration();
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        // fast-path:对剩余 TTL 仍较大的"新鲜"缓存项,跳过本 handler 全部逻辑
        // (不 GET value、不判定 policy),让 ActualCacheHandler 走原生 GET 完成
        // hit 处理。把热路径从"early handler GET + actual GET"两次 RTT 降为
        // "actual GET"一次 RTT。
        Long remainingTtl = redisTemplate.getExpire(context.getRedisKey(), TimeUnit.SECONDS);
        // getExpire 返回值约定:
        //   -2 = key 不存在(没必要 GET,ActualCacheHandler 会自己处理)
        //   -1 = key 存在但无过期(永不过期 → 不需要早刷新)
        //   null = Redis 命令失败(防御性:走原 GET 路径,行为字节等价)
        //   >= 0 = 剩余秒数
        if (remainingTtl != null && remainingTtl > FAST_PATH_TTL_SECONDS) {
            return HandlerResult.continueChain();
        }
        if (remainingTtl != null && remainingTtl < 0) {
            // -1 或 -2 都不需要本 handler 工作
            return HandlerResult.continueChain();
        }

        // 剩余 TTL 已落入刷新窗口(<=60s) 或 getExpire 失败(走 defensive GET)
        CachedValue cachedValue = (CachedValue) valueOperations.get(context.getRedisKey());

        if (cachedValue == null || cachedValue.checkExpired()) {
            // 缓存不存在或已过期，不预取，ActualCacheHandler 走原生 GET 路径
            // (prefetchDecision 保持 null,handleGet 回退 valueOperations.get)
            return HandlerResult.continueChain();
        }

        // 缓存命中：判定提前过期 + 一次性写入类型化 PrefetchDecision
        EarlyExpirationDecision decision = checkEarlyExpiration(context, cachedValue);
        boolean skipped = decision.needsRefresh() && decision.isSync();
        context.setPrefetchDecision(PrefetchDecision.of(skipped, cachedValue, decision));

        if (skipped) {
            // 同步提前过期：返回 skipAll，ActualCacheHandler 检查 prefetchDecision 后返回 miss
            log.debug("Sync early-expiration triggered, skipping actual cache: cacheName={}, key={}",
                      context.getCacheName(), context.getRedisKey());
            // 同步提前过期触发事件计数
            safeIncrementSemantic();
            return HandlerResult.skipAll();
        }

        // 不需要刷新或异步刷新，继续执行
        return HandlerResult.continueChain();
    }

    /**
     * 检查是否需要提前过期
     *
     * @param context 缓存上下文
     * @param cachedValue 缓存的值
     * @return 提前过期决策
     */
    private EarlyExpirationDecision checkEarlyExpiration(CacheContext context, CachedValue cachedValue) {
        boolean shouldRefresh = earlyExpirationPolicy.shouldRefresh(
            cachedValue.getCreatedTime(),
            cachedValue.getTtl(),
            context.getCacheOperation().getEarlyExpirationThreshold()
        );

        if (!shouldRefresh) {
            return EarlyExpirationDecision.noRefresh();
        }

        EarlyExpirationMode mode = resolveMode(context);

        log.info("Pre-refresh needed: cacheName={}, key={}, mode={}, remainingTtl={}s",
                 context.getCacheName(), context.getRedisKey(), mode, cachedValue.getRemainingTtl());

        if (mode == EarlyExpirationMode.ASYNC) {
            scheduleAsyncRefresh(context, cachedValue);
            return EarlyExpirationDecision.asyncRefresh();
        }

        statistics.incMisses(context.getCacheName());
        return EarlyExpirationDecision.syncRefresh();
    }

    /**
     * 解析提前过期模式
     */
    private EarlyExpirationMode resolveMode(CacheContext context) {
        EarlyExpirationMode mode = context.getCacheOperation().getEarlyExpirationMode();
        return mode != null ? mode : EarlyExpirationMode.SYNC;
    }

    /**
     * 安排异步提前过期任务 — 委派给 {@link #performAsyncRefresh(String, String, CachedValue)}。
     */
    private void scheduleAsyncRefresh(CacheContext context, CachedValue cachedValue) {
        String redisKey = context.getRedisKey();
        String cacheName = context.getCacheName();

        earlyExpirationExecutor.submit(redisKey,
                () -> performAsyncRefresh(redisKey, cacheName, cachedValue));

        log.info("Async early-expiration scheduled: cacheName={}, key={}", cacheName, redisKey);
    }

    /**
     * 异步提前过期任务体。
     *
     * <p>职责:
     * <ol>
     *   <li>读 live value:为 null → 调试日志 "key already missing" + return</li>
     *   <li>读 live TTL:介于 (0, REFRESH_GRACE_PERIOD_SECONDS) → 调试日志
     *       "below grace period" + return(避免刷新即将过期数据)</li>
     *   <li>{@link #atomicShortenTtlIfValueUnchanged}(redisKey, capturedValue) 走
     *       Lua CAS:value 未变才 expire,缩短 TTL 至宽限期
     *       <ul>
     *         <li>返回 true → 调试日志 "shortened TTL"</li>
     *         <li>返回 false → 调试日志 "value changed"(并发写覆盖)</li>
     *       </ul></li>
     *   <li>任意异常 → ERROR 日志(异常吞,不污染外层)</li>
     * </ol>
     *
     * <p>设计纪律:
     * <ul>
     *   <li><b>package-private 而非 private</b>:直接单测入口 —
     *       {@code EarlyExpirationHandlerTest} 可绕过 executor 直接调,
     *       验证 3 个决策分支 + 异常翻译,而无需制造并发竞态。
     *       同文件 {@code atomicShortenTtlIfValueUnchanged} 保持 {@code private}
     *       因其单测入口已由本方法覆盖。</li>
     *   <li><b>不返回 mainResult</b>:无返回值,3 决策分支各自有副作用(log + return);
     *       调用方不需要 mainResult,避免 split-knowledge。</li>
     *   <li><b>异常吞咽</b>:try/catch 在本方法体内,不向上抛。</li>
     * </ul>
     *
     * <p><b>deletion test</b>:把本方法删掉、内联回 submit lambda → 失去 seam 名 +
     * 单测入口 + 分支命名,复杂度上升。
     *
     * @param redisKey     缓存键(完整 Redis key)
     * @param cacheName    缓存名(用于 ERROR 日志)
     * @param capturedValue 触发本次异步刷新的原始缓存值(用于 Lua CAS 比对)
     */
    void performAsyncRefresh(String redisKey, String cacheName, CachedValue capturedValue) {
        try {
            CachedValue liveValue = (CachedValue) valueOperations.get(redisKey);

            if (liveValue == null) {
                log.debug("Async early-expiration: key already missing: {}", redisKey);
                return;
            }

            // 检查 TTL 是否即将过期（避免刷新已过期数据）
            long remainingTtl = liveValue.getRemainingTtl();
            if (remainingTtl > 0 && remainingTtl < REFRESH_GRACE_PERIOD_SECONDS) {
                log.debug("Async early-expiration skipped: key={} remainingTtl={}s is below grace period {}s",
                          redisKey, remainingTtl, REFRESH_GRACE_PERIOD_SECONDS);
                return;
            }

            boolean shortened = atomicShortenTtlIfValueUnchanged(redisKey, capturedValue);
            if (shortened) {
                log.debug("Async early-expiration shortened TTL: key={}, gracePeriod={}s",
                          redisKey, REFRESH_GRACE_PERIOD_SECONDS);
            } else {
                log.debug("Async early-expiration skipped: value changed: {}", redisKey);
            }
        } catch (Exception ex) {
            log.error("Async early-expiration failed: cacheName={}, key={}", cacheName, redisKey, ex);
        }
    }

    private boolean atomicShortenTtlIfValueUnchanged(String redisKey, CachedValue expectedValue) {
        return Boolean.TRUE.equals(redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Boolean>) connection -> {
            RedisSerializer<String> keySerializer = redisTemplate.getStringSerializer();

            byte[] keyBytes = keySerializer.serialize(redisKey);
            // 仅传 expectedValue 的 version 字段(8 字节)而非整个 serialized
            // value(O(N×payload_size)字节)—— 脚本 cjson 解析后比较。
            byte[] versionBytes = String.valueOf(expectedValue.getVersion())
                    .getBytes(StandardCharsets.UTF_8);
            byte[] ttlBytes = String.valueOf(REFRESH_GRACE_PERIOD_SECONDS).getBytes(StandardCharsets.UTF_8);

            Long result = connection.eval(
                EarlyExpirationScripts.ATOMIC_TTL_SHORTEN_SCRIPT.getBytes(StandardCharsets.UTF_8),
                ReturnType.INTEGER,
                1,
                keyBytes, versionBytes, ttlBytes
            );
            return result != null && result == 1;
        }));
    }

    /**
     * 获取提前过期决策（供其他 Handler 使用）
     *
     * @param context 缓存上下文
     * @return 提前过期决策
     */
    public static EarlyExpirationDecision getDecision(CacheContext context) {
        PrefetchDecision prefetch = context.getPrefetchDecision();
        return prefetch != null && prefetch.decision() != null
                ? prefetch.decision()
                : EarlyExpirationDecision.noRefresh();
    }
}
