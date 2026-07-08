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
 *   <li>原设计：提前过期逻辑在 ActualCacheHandler 中，通过回调实现</li>
 *   <li>新设计：独立为 Handler，直接检查缓存值并做出决策</li>
 *   <li>GET 操作时，先获取缓存值，判断是否需要提前过期</li>
 *   <li>如果需要同步刷新，返回 skipAll，ActualCacheHandler 检查标记后返回 miss</li>
 * </ul>
 */
@Slf4j
@Component
@HandlerPriority(HandlerOrder.EARLY_EXPIRATION)
public class EarlyExpirationHandler extends AbstractCacheHandler {

    private static final long REFRESH_GRACE_PERIOD_SECONDS = 5;

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
     * ADR-0018 — 语义 counter 元数据声明。Path C 后续(WS-1.4)：
     * 同步提前过期触发事件计数。
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
        // 先尝试获取缓存值
        CachedValue cachedValue = (CachedValue) valueOperations.get(context.getRedisKey());

        if (cachedValue == null || cachedValue.checkExpired()) {
            // 缓存不存在或已过期，不预取，ActualCacheHandler 走原生 GET 路径
            // (prefetchDecision 保持 null,handleGet 回退 valueOperations.get)
            return HandlerResult.continueChain();
        }

        // 缓存命中：判定提前过期 + 一次性写入类型化 PrefetchDecision（ADR-0036 / Round 26 C1）
        EarlyExpirationDecision decision = checkEarlyExpiration(context, cachedValue);
        boolean skipped = decision.needsRefresh() && decision.isSync();
        context.setPrefetchDecision(PrefetchDecision.of(skipped, cachedValue, decision));

        if (skipped) {
            // 同步提前过期：返回 skipAll，ActualCacheHandler 检查 prefetchDecision 后返回 miss
            log.debug("Sync early-expiration triggered, skipping actual cache: cacheName={}, key={}",
                      context.getCacheName(), context.getRedisKey());
            // WS-1.4 per-handler tag:同步提前过期触发事件计数
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
     * 安排异步提前过期任务 — ADR-0057 收敛后的 1 行委派。
     *
     * <p>原 22 行 lambda body 内联捕获 5 状态(redisKey + cacheName + valueOperations
     * 字段 + REFRESH_GRACE_PERIOD_SECONDS 常量 + cachedValue 参数),3 个决策分支
     * (key 缺失 / 宽限期内 / CAS 成功失败)各自带独立 log,0 单元测试。已抽出为
     * package-private {@link #performAsyncRefresh(String, String, CachedValue)},
     * submit 退化为单行委派,decision 命名 + 直接单测入口。
     */
    private void scheduleAsyncRefresh(CacheContext context, CachedValue cachedValue) {
        String redisKey = context.getRedisKey();
        String cacheName = context.getCacheName();

        earlyExpirationExecutor.submit(redisKey,
                () -> performAsyncRefresh(redisKey, cacheName, cachedValue));

        log.info("Async early-expiration scheduled: cacheName={}, key={}", cacheName, redisKey);
    }

    /**
     * 异步提前过期任务体 — ADR-0057 抽出的 deep seam.
     *
     * <p>职责(从原 scheduleAsyncRefresh 内联 lambda 平移,逐字保留原行为):
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
     *   <li>任意异常 → ERROR 日志(异常已吞,不污染外层)</li>
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
     *   <li><b>异常吞咽保留</b>:try/catch 在原 lambda 内(平移至此方法体),本方法不
     *       向上抛 — 与原匿名 lambda 行为字节等价。</li>
     * </ul>
     *
     * <p><b>deletion test</b>:把本方法删掉、内联回 submit lambda → 22 行 + 5 状态
     * + 0 测试,代码量相同但失去 seam 名 + 单测入口 + 分支命名 — 复杂度上升。
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
            @SuppressWarnings("unchecked")
            RedisSerializer<Object> valueSerializer = (RedisSerializer<Object>) redisTemplate.getValueSerializer();

            byte[] keyBytes = keySerializer.serialize(redisKey);
            byte[] expectedValueBytes = valueSerializer.serialize(expectedValue);
            byte[] ttlBytes = String.valueOf(REFRESH_GRACE_PERIOD_SECONDS).getBytes(StandardCharsets.UTF_8);

            Long result = connection.eval(
                EarlyExpirationScripts.ATOMIC_TTL_SHORTEN_SCRIPT.getBytes(StandardCharsets.UTF_8),
                ReturnType.INTEGER,
                1,
                keyBytes, expectedValueBytes, ttlBytes
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
