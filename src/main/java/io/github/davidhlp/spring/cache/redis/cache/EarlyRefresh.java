package io.github.davidhlp.spring.cache.redis.cache;




import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.EarlyExpirationDecision;
import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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
 * <p><b>Boundary</b>: thread pool / deduplication / retry / cleanup scheduling /
 * shutdown remain owned by {@link ThreadPoolEarlyExpirationExecutor}; chain-side
 * cancellation still goes through the single {@link RefreshCancellation} method seam.
 * This module only clarifies ownership of the early-expiration concept.
 */
@Slf4j
@Component
class EarlyRefresh {

    /** 宽限期:剩余 TTL 低于此值时不再安排刷新(即将过期的数据不值得刷)。 */
    private static final long REFRESH_GRACE_PERIOD_SECONDS = 5;

    /** Compare bytes and live TTL atomically; Lua never decodes long versions. */
    private static final String ATOMIC_TTL_SHORTEN_SCRIPT =
        "local current = redis.call('get', KEYS[1]); " +
        "if not current then return 0 end; " +
        "if current ~= ARGV[1] then return 2 end; " +
        "local ttl = redis.call('pttl', KEYS[1]); " +
        "if ttl >= 0 and ttl <= tonumber(ARGV[2]) then return 3 end; " +
        "redis.call('pexpire', KEYS[1], ARGV[2]); return 1";

    private final Clock clock;
    private final ThreadPoolEarlyExpirationExecutor earlyExpirationExecutor;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ValueOperations<String, Object> valueOperations;

    EarlyRefresh(
            Clock clock,
            ThreadPoolEarlyExpirationExecutor earlyExpirationExecutor,
            @Qualifier("redisCacheTemplate") RedisTemplate<String, Object> redisTemplate,
            ValueOperations<String, Object> valueOperations) {
        this.clock = clock;
        this.earlyExpirationExecutor = earlyExpirationExecutor;
        this.redisTemplate = redisTemplate;
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
     * 同步模式只返回决策,由链节点决定「跳过实际缓存读、返回 miss」。
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
        boolean shouldRefresh = shouldRefresh(
                cachedValue.getCreatedTime(),
                cachedValue.getTtl(),
                context.policy().earlyExpirationThreshold());

        if (!shouldRefresh) {
            return EarlyExpirationDecision.noRefresh();
        }

        EarlyExpirationMode mode = resolveMode(context);

        log.debug("Pre-refresh needed: cacheName={}, keyFingerprint={}, mode={}, remainingTtl={}s",
                 context.getCacheName(), FailureReport.fingerprint(context.getRedisKey()), mode, cachedValue.getRemainingTtl());

        if (mode == EarlyExpirationMode.ASYNC) {
            scheduleAsyncRefresh(context, cachedValue);
            return EarlyExpirationDecision.asyncRefresh();
        }

        // GET 统计统一由 RedisProCacheWriter 按链结果记录。
        return EarlyExpirationDecision.syncRefresh();
    }

    /**
     * 判断缓存项是否进入提前刷新窗口。
     */
    private boolean shouldRefresh(long createdTime, long ttlSeconds, double threshold) {
        if (ttlSeconds <= 0 || threshold <= 0 || threshold >= 1) {
            return false;
        }

        long elapsedTime = clock.millis() - createdTime;
        long totalTime = ttlSeconds * 1000;
        double usedRatio = (double) elapsedTime / totalTime;
        return usedRatio >= (1 - threshold);
    }

    /**
     * 解析提前过期模式
     */
    private EarlyExpirationMode resolveMode(CacheContext context) {
        EarlyExpirationMode mode = context.policy().earlyExpirationMode();
        return mode != null ? mode : EarlyExpirationMode.SYNC;
    }

    /**
     * 安排异步提前过期任务 — 委派给 {@link #performAsyncRefresh(String, CachedValue)}。
     */
    private void scheduleAsyncRefresh(CacheContext context, CachedValue cachedValue) {
        String redisKey = context.getRedisKey();
        String cacheName = context.getCacheName();

        earlyExpirationExecutor.submit(redisKey,
                () -> performAsyncRefresh(redisKey, cachedValue));

        log.debug("Async early-expiration evaluated: cacheName={}, keyFingerprint={}",
                cacheName, FailureReport.fingerprint(redisKey));
    }

    /**
     * Validates the exact Java long version, then CASes the original wire snapshot.
     * Cancellation is best-effort; the CAS still protects a replacement after cancellation.
     */
    void performAsyncRefresh(String redisKey, CachedValue capturedValue) {
        byte[] keyBytes = redisTemplate.getStringSerializer().serialize(redisKey);
        byte[] snapshot = redisTemplate.execute(
                (org.springframework.data.redis.core.RedisCallback<byte[]>) connection ->
                        connection.stringCommands().get(keyBytes));
        if (snapshot == null) {
            earlyExpirationExecutor.recordOutcome(RefreshTaskMetrics.Outcome.MISSING);
            return;
        }
        Object rawLiveValue = redisTemplate.getValueSerializer().deserialize(snapshot);
        if (!(rawLiveValue instanceof CachedValue liveValue)
                || liveValue.getVersion() != capturedValue.getVersion()) {
            earlyExpirationExecutor.recordOutcome(RefreshTaskMetrics.Outcome.VERSION_CHANGED);
            return;
        }
        long remainingTtl = liveValue.getRemainingTtl();
        if (remainingTtl > 0 && remainingTtl < REFRESH_GRACE_PERIOD_SECONDS) {
            earlyExpirationExecutor.recordOutcome(RefreshTaskMetrics.Outcome.BELOW_GRACE);
            return;
        }
        Long result = redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection ->
                connection.eval(ATOMIC_TTL_SHORTEN_SCRIPT.getBytes(StandardCharsets.UTF_8),
                        ReturnType.INTEGER, 1, keyBytes, snapshot,
                        String.valueOf(REFRESH_GRACE_PERIOD_SECONDS * 1000).getBytes(StandardCharsets.UTF_8)));
        RefreshTaskMetrics.Outcome outcome = switch (result == null ? 0 : result.intValue()) {
            case 1 -> RefreshTaskMetrics.Outcome.SHORTENED;
            case 2 -> RefreshTaskMetrics.Outcome.SNAPSHOT_CHANGED;
            case 3 -> RefreshTaskMetrics.Outcome.TTL_UNCHANGED;
            default -> RefreshTaskMetrics.Outcome.MISSING;
        };
        earlyExpirationExecutor.recordOutcome(outcome);
        log.debug("Async early-expiration: keyFingerprint={}, outcome={}",
                FailureReport.fingerprint(redisKey), outcome);
    }
}
