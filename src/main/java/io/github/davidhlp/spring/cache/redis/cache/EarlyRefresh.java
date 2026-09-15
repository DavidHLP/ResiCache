package io.github.davidhlp.spring.cache.redis.cache;




import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.EarlyExpirationDecision;
import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 提前过期刷新模块 —— 「读值 → 判定 → 调度 → CAS 缩短 TTL」的全部责任在一处。
 *
 * <p><b>为什么独立成类</b>(deletion test):本概念此前横跨
 * {@code EarlyExpirationHandler}(决策 + 异步任务体 + Lua CAS)、
 * {@link ThreadPoolEarlyExpirationExecutor}(池 / 去重 / 重试 / 指标)与
 * {@link RefreshRetryPolicy}(重试循环)。其中「决策读到的值」必须原样交给后续
 * {@link EarlyExpirationDecision} 与{@code ActualCacheHandler}复用(避免二次 Redis GET),
 * 「调度与 CAS」又必须在同一个生命周期里 —— 三者分开后,读值、决策与刷新对象之间只能靠
 * 参数传递维系。收敛到本模块后:
 *
 * <ul>
 *   <li><b>locality</b>:一次 GET 评估的读值 / 判定 / 调度 / CAS 同处一文件,失败路径与诊断规则唯一</li>
 *   <li><b>leverage</b>:{@link EarlyExpirationHandler} 只剩「链节点适配」——shouldHandle +
 *       把评估结果翻译成 {@code PrefetchDecision} 与 {@code HandlerResult}</li>
 *   <li><b>testability</b>:异步刷新任务体({@link #performAsyncRefresh})可在无责任链的情况下直接驱动</li>
 * </ul>
 *
 * <p><b>边界(ADR-0001 §11)</b>:线程池 / 去重 / 重试 / 清理调度 / shutdown 仍归
 * {@link ThreadPoolEarlyExpirationExecutor};chain 侧取消仍只经 {@link RefreshCancellation}
 * 单一方法 seam。本模块不新增对外 SPI,只是把「谁拥有提前过期这个概念」讲清楚。
 */
@Slf4j
@Component
class EarlyRefresh {

    /** 宽限期:剩余 TTL 低于此值时不再安排刷新(即将过期的数据不值得刷)。 */
    private static final long REFRESH_GRACE_PERIOD_SECONDS = 5;

    private final EarlyExpirationPolicy earlyExpirationPolicy;
    private final ThreadPoolEarlyExpirationExecutor earlyExpirationExecutor;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheStatisticsCollector statistics;
    private final ValueOperations<String, Object> valueOperations;

    EarlyRefresh(
            EarlyExpirationPolicy earlyExpirationPolicy,
            ThreadPoolEarlyExpirationExecutor earlyExpirationExecutor,
            @Qualifier("redisCacheTemplate") RedisTemplate<String, Object> redisTemplate,
            CacheStatisticsCollector statistics,
            ValueOperations<String, Object> valueOperations) {
        this.earlyExpirationPolicy = earlyExpirationPolicy;
        this.earlyExpirationExecutor = earlyExpirationExecutor;
        this.redisTemplate = redisTemplate;
        this.statistics = statistics;
        this.valueOperations = valueOperations;
    }

    /**
     * 一次 GET 的提前过期评估 —— 读到的缓存值 + 决策。
     *
     * <p>{@code cachedValue} 交给链后续节点复用,避免同一请求二次 Redis GET;
     * {@code decision} 为 {@link EarlyExpirationDecision#noRefresh()} 时表示命中但无需刷新。
     */
    record Evaluation(CachedValue cachedValue, EarlyExpirationDecision decision) {
    }

    /**
     * 评估是否需要提前刷新 —— 一次 GET 的读 + 判定收口。
     *
     * <p>异步模式在本方法内完成调度(值已捕获,任务只做 CAS 缩短 TTL);
     * 同步模式只返回决策并自增 miss 计数,由链节点决定「跳过实际缓存读、返回 miss」。
     *
     * @param context 缓存上下文(GET 操作)
     * @return 评估结果;缓存未命中 / 已过期 / 非 {@link CachedValue} 时返回 {@code null}
     *         (调用方不写 {@code PrefetchDecision},后续节点自行回退读取)
     */
    @Nullable
    Evaluation evaluate(CacheContext context) {
        // 必须先取得完整 CachedValue，再按用户配置的比例阈值判断。
        // 绝对 TTL 快速路径会绕过高 TTL + 高龄缓存的合法刷新窗口，导致
        // 判定行为与 earlyExpirationThreshold 不一致。
        Object rawValue = valueOperations.get(context.getRedisKey());
        CachedValue cachedValue = rawValue instanceof CachedValue cv ? cv : null;

        if (cachedValue == null || cachedValue.checkExpired()) {
            // 缓存不存在或已过期，不预取，后续节点走原生 GET 路径
            return null;
        }
        return new Evaluation(cachedValue, checkEarlyExpiration(context, cachedValue));
    }

    /**
     * 检查是否需要提前过期
     */
    private EarlyExpirationDecision checkEarlyExpiration(CacheContext context, CachedValue cachedValue) {
        boolean shouldRefresh = earlyExpirationPolicy.shouldRefresh(
            cachedValue.getCreatedTime(),
            cachedValue.getTtl(),
            context.policy().earlyExpirationThreshold()
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
        EarlyExpirationMode mode = context.policy().earlyExpirationMode();
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
     *   <li>读 live TTL:介于 (0, {@value #REFRESH_GRACE_PERIOD_SECONDS}) → 调试日志
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
     *   <li><b>package-private 而非 private</b>:直接单测入口 ——
     *       {@code EarlyExpirationHandlerIntegrationTest} 可绕过 executor 直接调,
     *       验证 3 个决策分支 + 异常翻译,而无需制造并发竞态。
     *       同文件 {@code atomicShortenTtlIfValueUnchanged} 保持 {@code private}
     *       因其单测入口已由本方法覆盖。</li>
     *   <li><b>不返回 mainResult</b>:无返回值,3 决策分支各自有副作用(log + return);
     *       调用方不需要 mainResult,避免 split-knowledge。</li>
     *   <li><b>异常吞咽</b>:try/catch 在本方法体内,不向上抛。</li>
     * </ul>
     *
     * @param redisKey     缓存键(完整 Redis key)
     * @param cacheName    缓存名(用于 ERROR 日志)
     * @param capturedValue 触发本次异步刷新的原始缓存值(用于 Lua CAS 比对)
     */
    void performAsyncRefresh(String redisKey, String cacheName, CachedValue capturedValue) {
        try {
            Object rawLiveValue = valueOperations.get(redisKey);
            if (rawLiveValue == null) {
                log.debug("Async early-expiration: key already missing: {}", redisKey);
                return;
            }
            if (!(rawLiveValue instanceof CachedValue liveValue)) {
                log.debug("Async early-expiration skipped: unsupported cached value type: key={}, type={}",
                        redisKey, rawLiveValue.getClass().getName());
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
            // ADR-0001 §15 key 隐私:ERROR 只带 cacheName + keyFingerprint,不带 raw key
            log.error("Async early-expiration failed: cacheName={}, keyFingerprint={}",
                    cacheName, FailureDiagnostics.keyFingerprint(redisKey), ex);
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
}
