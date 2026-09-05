package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.cache.metrics.CacheMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.lang.Nullable;

/**
 * 缓存实例的 Micrometer 指标注册 + 记录 deep seam — 与 {@link CacheMetrics} 读侧快照配对的写侧 seam.
 *
 * <p><b>动机（deletion test）</b>：把 metric 注册 + 记录收口为单一 deep seam，避免
 * "采集哪些指标 + 怎么采集"的概念散到字段声明、构造期注册、override 自增、metrics() 快照读取
 * 多处 —— 新增一个指标（如 hit-ratio / p99 / 复合 timer）需同时改多处，locality 失守。
 *
 * <p><b>与 {@link CacheMetrics} 的对称性</b>：本 seam 是"写侧"（注册 + 记录），与读侧
 * 快照（{@link CacheMetrics}）配对形成<em>指标领域</em> 完整边界。读侧只读、写侧只写 — 关注点分离。
 *
 * <p><b>与 {@link RedisProCacheTimers} 的关系</b>：{@link RedisProCacheTimers} 是 metric 原语
 * helper（{@code registerTimer} / {@code registerCounter} / {@code timed} / {@code timedGet} /
 * {@code safeIncrement}），本身是工具类无状态。本类是 metric 集合的<em>容器</em>（6 字段 + 6
 * 业务语义方法），把原语按"缓存实例"的语义组装起来。两层 seam 形成 composition — 删除任意
 * 一层，复杂度上浮。
 *
 * <p><b>公开方法（业务语义）</b>：
 * <ul>
 *   <li>{@link #recordGet} — get 路径的 timing + 命中/未命中计数委派（hit/miss 由调用方记）</li>
 *   <li>{@link #recordPut} — put 路径的 timing + 写计数</li>
 *   <li>{@link #recordEvict} — evict 路径的 timing + 淘汰计数</li>
 *   <li>{@link #recordClear} — clear 路径的 timing（无计数，clear 是 batch 操作）</li>
 *   <li>{@link #metrics()} — 返回当前 cache 实例的不可变指标快照</li>
 * </ul>
 *
 * <p><b>null-safe 语义</b>：{@link MeterRegistry} 为 null 时（即未启用指标），全部 6 个内部
 * 字段为 null，所有 record 方法走 no-op 路径。
 *
 * <p><b>线程安全</b>：本类仅在 cache 构造期由单线程初始化；运行期 record 方法调
 * {@link Timer#record} / {@link Counter#increment}（Micrometer 自身线程安全）。metrics() 仅读
 * 字段，多线程并发安全。
 *
 * <p><b>deletion test</b>：删本类 → metric 注册 + 自增样板在 {@link RedisProCache} 调用点重现，
 * locality 失守 → 真 seam。
 */
final class RedisProCacheMetricsRegistry {

    // ==================== metric 名常量（业务语义 + 单一事实源） ====================

    private static final String TIMER_GET = "resicache.cache.get";
    private static final String TIMER_PUT = "resicache.cache.put";
    private static final String TIMER_EVICT = "resicache.cache.evict";
    private static final String COUNTER_HIT = "resicache.cache.hit";
    private static final String COUNTER_MISS = "resicache.cache.miss";
    private static final String COUNTER_PUT = "resicache.cache.put.count";
    private static final String COUNTER_EVICT = "resicache.cache.evict.count";

    private static final String DESC_GET_TIMER = "Time spent getting cache entries";
    private static final String DESC_PUT_TIMER = "Time spent putting cache entries";
    private static final String DESC_EVICT_TIMER = "Time spent evicting cache entries";
    private static final String DESC_HIT = "Cache hit count";
    private static final String DESC_MISS = "Cache miss count";
    private static final String DESC_PUT = "Cache put count";
    private static final String DESC_EVICT = "Cache evict count";

    private final String cacheName;

    // 写侧 6 字段：3 Timer + 4 Counter
    // 注：clear 路径无 Counter（batch 操作语义不适合计数），仅 Timer
    @Nullable
    private final Timer getTimer;
    @Nullable
    private final Timer putTimer;
    @Nullable
    private final Timer evictTimer;
    @Nullable
    private final Counter hitCounter;
    @Nullable
    private final Counter missCounter;
    @Nullable
    private final Counter putCounter;
    @Nullable
    private final Counter evictCounter;

    /**
     * 构造期一次性注册 6 个 metric — 在 cache 构造期调用一次，运行期 record 路径直接复用。
     *
     * <p>内部委派 {@link RedisProCacheTimers} 原语（registerTimer / registerCounter）保证 null-safe 语义。
     *
     * @param meterRegistry Micrometer 注册表（可为 null → 全部 6 字段为 null）
     * @param cacheName     cache 标识，作为 {@code tags("cache", cacheName)} 写入每个 metric
     */
    public RedisProCacheMetricsRegistry(@Nullable MeterRegistry meterRegistry, String cacheName) {
        this.cacheName = cacheName;
        this.getTimer = RedisProCacheTimers.registerTimer(meterRegistry, TIMER_GET, DESC_GET_TIMER, cacheName);
        this.putTimer = RedisProCacheTimers.registerTimer(meterRegistry, TIMER_PUT, DESC_PUT_TIMER, cacheName);
        this.evictTimer = RedisProCacheTimers.registerTimer(meterRegistry, TIMER_EVICT, DESC_EVICT_TIMER, cacheName);
        this.hitCounter = RedisProCacheTimers.registerCounter(meterRegistry, COUNTER_HIT, DESC_HIT, cacheName);
        this.missCounter = RedisProCacheTimers.registerCounter(meterRegistry, COUNTER_MISS, DESC_MISS, cacheName);
        this.putCounter = RedisProCacheTimers.registerCounter(meterRegistry, COUNTER_PUT, DESC_PUT, cacheName);
        this.evictCounter = RedisProCacheTimers.registerCounter(meterRegistry, COUNTER_EVICT, DESC_EVICT, cacheName);
    }

    // ==================== 业务方法（get / put / evict / clear） ====================

    /**
     * 记录 get 路径 — 仅记录 timer。hit/miss 计数由调用方根据 cache 返回值（非 null / null）显式
     * 调 {@link #recordHit()} / {@link #recordMiss()}。原因:hit/miss 判定依赖 cache 自身的
     * 返回值类型(可能是 {@code ValueWrapper} / 反序列化值 / null-value 包装),seam 不感知。
     *
     * <p>用于 {@link RedisProCache#get(Object)} / {@link RedisProCache#get(Object, Class)} /
     * {@link RedisProCache#get(Object, java.util.concurrent.Callable)} — 这 3 个重载共用同一 timer。
     *
     * @param body  实际的 get 操作（不可为 null）
     * @param <T>   返回值类型
     * @return body.get() 的结果
     */
    public <T> T recordGet(Supplier<T> body) {
        return RedisProCacheTimers.timedGet(getTimer, body);
    }

    /**
     * 记录 hit 计数（get 返回非 null 时调用）— null-safe。
     */
    public void recordHit() {
        RedisProCacheTimers.safeIncrement(hitCounter);
    }

    /**
     * 记录 miss 计数（get 返回 null 时调用）— null-safe。
     */
    public void recordMiss() {
        RedisProCacheTimers.safeIncrement(missCounter);
    }

    /**
     * 记录 put 路径 — 同时记录 timer + 写计数。
     *
     * @param body 实际的 put 操作（不可为 null），包含 {@code super.put} 与必要的 metric 副作用
     */
    public void recordPut(Runnable body) {
        if (putTimer == null) {
            body.run();
            increment(putCounter);
            return;
        }
        long start = System.nanoTime();
        try {
            body.run();
        } finally {
            putTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            increment(putCounter);
        }
    }

    /**
     * 记录 evict 路径 — 同时记录 timer + 淘汰计数。
     *
     * @param body 实际的 evict 操作（不可为 null）
     */
    public void recordEvict(Runnable body) {
        if (evictTimer == null) {
            body.run();
            increment(evictCounter);
            return;
        }
        long start = System.nanoTime();
        try {
            body.run();
        } finally {
            evictTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            increment(evictCounter);
        }
    }

    /**
     * 记录 clear 路径 — 仅记录 timer（无 Counter，batch 操作语义）。
     *
     * @param body 实际的 clear 操作（不可为 null）
     */
    public void recordClear(Runnable body) {
        RedisProCacheTimers.timed(evictTimer, body);
    }

    /**
     * 当前 cache 实例的指标快照。
     *
     * <p>Counter 字段为 null 时（registry 缺失）对应字段为 0L。
     *
     * @return 不可变指标快照
     */
    public CacheMetrics metrics() {
        return new CacheMetrics(
                countOf(hitCounter),
                countOf(missCounter),
                countOf(putCounter),
                countOf(evictCounter));
    }

    /**
     * 当前 cache 名称（仅暴露给持有 seam 的 caller 调试 / 日志用）。
     *
     * @return 构造期传入的 cache 名称
     */
    String cacheName() {
        return cacheName;
    }

    // ==================== 私有 helper ====================

    private static void increment(@Nullable Counter counter) {
        RedisProCacheTimers.safeIncrement(counter);
    }

    private static long countOf(@Nullable Counter counter) {
        return counter != null ? (long) counter.count() : 0L;
    }
}
