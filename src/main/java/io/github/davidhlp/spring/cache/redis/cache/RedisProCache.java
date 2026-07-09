package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.cache.LoaderOrchestrator.LoadOutcome;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.util.concurrent.Callable;

@Slf4j
public class RedisProCache extends RedisCache {

    private final Timer getTimer;
    private final Timer putTimer;
    private final Timer evictTimer;
    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter putCounter;
    private final Counter evictCounter;

    /** 方法级 operation 元数据解析器 — 仅 lookupOperation 使用;null 时关闭元数据查找。 */
    private final CacheOperationResolver operationResolver;

    /**
     * Loader 路径编排器 — Round 49 / ADR-0062 抽出的 deep seam。
     *
     * <p>本类不再持有 {@code bloomGate} / {@code syncSupport} / {@code syncLockTimeout}
     * 3 个 protection 协作 bean — 这些 seam 全部下沉到 {@link LoaderOrchestrator},
     * 由本类在构造期一次性 build 后委派 {@link LoaderOrchestrator#orchestrate}。
     *
     * <p>设计纪律:orchestrator 不持有本类引用,委派通过回调实现 —
     * {@link #put} 闭包(preserve metrics)+ {@code super.get}/{@code super.get(key, loader)}
     * (via 参数 {@code this})。
     */
    private final LoaderOrchestrator loaderOrchestrator;

    /**
     * 构造 ResiCache 实例 — Round 5 / ADR-0014 收敛后的唯一构造入口.
     *
     * <p><b>单一 seam</b>:本类是 ResiCache 与 Spring {@code RedisCache} 的扩展点。
     * 全部可选特性收口到单一 {@link ResiCacheFeatures} 值对象(取代原 4 个位置可空参数),
     * 「null = 该特性禁用」的契约只存在于 {@link ResiCacheFeatures} 一处,不再由本构造器
     * 逐参数重述。测试用 {@link ResiCacheFeatures#none()} 或 builder 显式声明启用的特性。
     *
     * <p><b>ADR-0057 (Round 43)</b>:{@code redisCacheRegister} + {@code methodMetadataResolver}
     * 已合并为单一 {@link CacheOperationResolver}。
     *
     * <p><b>ADR-0062 (Round 49)</b>:loader 路径编排逻辑 + 3 个 protection 协作 bean
     * ({@code bloomGate} / {@code syncSupport} / {@code syncLockTimeout}) 全部下沉至
     * {@link LoaderOrchestrator};本构造器只剩 metrics 装配 + operation 解析器 + orchestrator
     * build(3 行委派)。
     *
     * <p><b>参数契约</b>:
     * <ul>
     *   <li>{@code name / cacheWriter / cacheConfiguration} —— 必传,转发给
     *       {@link RedisCache#super(String, RedisCacheWriter, RedisCacheConfiguration)}</li>
     *   <li>{@code features} —— 可选特性集合(见 {@link ResiCacheFeatures};各字段 null 表示禁用)</li>
     * </ul>
     *
     * <p><b>Round 22 收敛</b>(ADR-0031):timing & counter 注册与 null-safe 调用已迁移至
     * {@link RedisProCacheTimers} 工具 seam。
     */
    public RedisProCache(
            String name,
            RedisCacheWriter cacheWriter,
            RedisCacheConfiguration cacheConfiguration,
            ResiCacheFeatures features) {
        super(name, cacheWriter, cacheConfiguration);
        MeterRegistry meterRegistry = features.getMeterRegistry();
        this.getTimer = RedisProCacheTimers.registerTimer(meterRegistry, "resicache.cache.get",
                "Time spent getting cache entries", name);
        this.putTimer = RedisProCacheTimers.registerTimer(meterRegistry, "resicache.cache.put",
                "Time spent putting cache entries", name);
        this.evictTimer = RedisProCacheTimers.registerTimer(meterRegistry, "resicache.cache.evict",
                "Time spent evicting cache entries", name);
        this.hitCounter = RedisProCacheTimers.registerCounter(meterRegistry, "resicache.cache.hit",
                "Cache hit count", name);
        this.missCounter = RedisProCacheTimers.registerCounter(meterRegistry, "resicache.cache.miss",
                "Cache miss count", name);
        this.putCounter = RedisProCacheTimers.registerCounter(meterRegistry, "resicache.cache.put.count",
                "Cache put count", name);
        this.evictCounter = RedisProCacheTimers.registerCounter(meterRegistry, "resicache.cache.evict.count",
                "Cache evict count", name);
        this.operationResolver = features.getOperationResolver();
        // ADR-0062:loader 路径编排器 build — 委派 bloomGate/syncSupport/syncLockTimeout + 1 putAfterLoad 闭包;
        // orchestrator 与本类解耦,通过闭包 + super 引用完成 cache-specific 操作。
        this.loaderOrchestrator = new LoaderOrchestrator(
                features.getBloomGate(),
                features.getSyncSupport(),
                features.getSyncLockTimeout());
    }

    @Override
    public ValueWrapper get(Object key) {
        return RedisProCacheTimers.timedGet(getTimer, () -> {
            ValueWrapper result = super.get(key);
            if (result != null) {
                RedisProCacheTimers.safeIncrement(hitCounter);
            } else {
                RedisProCacheTimers.safeIncrement(missCounter);
            }
            return result;
        });
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        return RedisProCacheTimers.timedGet(getTimer, () -> {
            T result = super.get(key, type);
            if (result != null) {
                RedisProCacheTimers.safeIncrement(hitCounter);
            } else {
                RedisProCacheTimers.safeIncrement(missCounter);
            }
            return result;
        });
    }

    /**
     * Loader 路径主入口 — Round 49 / ADR-0062 收敛后:
     * 编排逻辑(bloom 短路 / sync vs default 调度 / locked-load 主体)已全部下沉到
     * {@link LoaderOrchestrator#orchestrate},本方法主体退化为
     * <ol>
     *   <li>timed wrap(getTimer)</li>
     *   <li>委派 orchestrator.orchestrate(...) 返回 {@link LoadOutcome}</li>
     *   <li>switch 翻译 3 态 → 路径返回 / miss 自增 / 异常翻译</li>
     * </ol>
     * 行为字节等价于 Round 47 / ADR-0057 / C3 的内联 9 行版本 — miss counter 自增
     * 次数对齐(bloom 短路 1 次 / 失败路径 1 次 / 成功路径 0 次),异常翻译规则对齐
     * (RuntimeException 直接抛 / checked Exception 翻译为 RuntimeException)。
     *
     * <p>4 个 callback 一次性 capture 在此处:
     * <ul>
     *   <li>{@code redisKeyFn} → {@link #deriveRedisKey}(super.createCacheKey) — BloomGate/SyncSupport 用</li>
     *   <li>{@code doubleCheckFn} → {@link #doubleCheckLookup}(super.get) — 锁内双检,绕过 override 不打 metrics</li>
     *   <li>{@code putAfterLoad} → {@code (k, v) -> put(k, v)} — 走 override,保留 putTimer + putCounter</li>
     *   <li>{@code defaultLoadFn} → {@link #defaultLoad}(super.get(key, loader)) — Spring local-lock</li>
     * </ul>
     */
    @Override
    public <T> T get(Object key, Callable<T> loader) {
        return RedisProCacheTimers.timedGet(getTimer, () -> {
            RedisCacheableOperation operation = lookupOperation();
            LoadOutcome<T> outcome = loaderOrchestrator.orchestrate(
                    getName(),
                    this::deriveRedisKey,
                    this::doubleCheckLookup,
                    (k, v) -> put(k, v),
                    this::defaultLoad,
                    loader,
                    key,
                    operation);
            return switch (outcome) {
                case LoaderOrchestrator.BloomShortCircuited<T> ignored -> {
                    RedisProCacheTimers.safeIncrement(missCounter);
                    yield null;
                }
                case LoaderOrchestrator.Loaded<T>(T value) -> value;
                case LoaderOrchestrator.LoadFailed<T>(Throwable cause) -> {
                    RedisProCacheTimers.safeIncrement(missCounter);
                    throw translateFailure(cause, key);
                }
            };
        });
    }

    /**
     * 派生 Redis key — 包私有 callback 注入 orchestrator(RedisCache.createCacheKey 是 protected,
     * 无法从 {@code LoaderOrchestrator} 直接访问,通过本方法透传)。
     */
    private String deriveRedisKey(Object key) {
        return super.createCacheKey(key);
    }

    /**
     * 锁内双检的 cache 读原语 — 走 {@code super.get(key)} 绕过本类 override,避免双检误计 hit/miss。
     * metrics 记录在外层 {@code get(key, loader)} 中唯一完成。
     */
    private Cache.ValueWrapper doubleCheckLookup(Object key) {
        return super.get(key);
    }

    /**
     * Default load 原语 — 委派 Spring Cache 本地锁路径;失败异常透传给 caller 翻译。
     */
    @SuppressWarnings("unchecked")
    private <T> T defaultLoad(Object key, Callable<T> loader) {
        return (T) super.get(key, loader);
    }

    /**
     * 失败异常翻译 — 把 orchestrator 透传的 {@link Throwable} 翻译为本方法契约的
     * {@link RuntimeException}:RuntimeException 直接抛(保留原始栈),
     * checked Exception 包装为 {@link RuntimeException}。
     *
     * <p>{@link Cache.ValueRetrievalException} 是 Spring 抽象层契约
     * (extends NestedRuntimeException) → RuntimeException,直接抛。
     */
    private RuntimeException translateFailure(Throwable cause, Object key) {
        if (cause instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException("Failed to load cache value for key: " + key, cause);
    }

    /**
     * 查找当前方法的缓存操作元数据 —— ADR-0057 收敛后的 1 行委派。
     *
     * <p>原 4 行镜像协议(ThreadLocal key null-check + register 查询 + 缺日志)已迁至
     * {@link CacheOperationResolver#resolve(String)};本方法退化委派。{@code operationResolver}
     * 为 null 时直接返回 null(测试场景关闭元数据查找,行为与原 {@code redisCacheRegister=null} 等价)。
     */
    private RedisCacheableOperation lookupOperation() {
        return operationResolver == null ? null : operationResolver.resolve(getName());
    }

    @Override
    public void put(Object key, Object value) {
        RedisProCacheTimers.timed(putTimer, () -> {
            super.put(key, value);
            RedisProCacheTimers.safeIncrement(putCounter);
        });
    }

    @Override
    public void evict(Object key) {
        RedisProCacheTimers.timed(evictTimer, () -> {
            super.evict(key);
            RedisProCacheTimers.safeIncrement(evictCounter);
        });
    }

    @Override
    public void clear() {
        RedisProCacheTimers.timed(evictTimer, super::clear);
    }

    /**
     * 当前缓存实例的指标快照 — ADR-0047 / C2 收敛.
     *
     * <p>本方法替代原 5 个 {@code getXCount()} 委托 + {@code getHitRate()} 派生方法,
     * 用 1 个 deep 方法返回不可变 {@link CacheMetrics} 值对象:
     * <ul>
     *   <li>4 个 Counter 字段(hit/miss/put/evict)在 factory 内一次性 null-safe 读取
     *       —— 等价于原 getter 的 {@code field != null ? count : 0L} 语义</li>
     *   <li>派生指标 {@code hitRate} 在 record 内集中计算,调用方不再做除法</li>
     * </ul>
     *
     * <p>Spring Boot Actuator 与 Micrometer Timer/Counter 注册维持原状
     * (本方法只读,不重置),外部观测不破坏。
     *
     * @return 当前缓存实例的指标快照(不可变)
     */
    public CacheMetrics metrics() {
        long hits = hitCounter != null ? (long) hitCounter.count() : 0L;
        long misses = missCounter != null ? (long) missCounter.count() : 0L;
        long puts = putCounter != null ? (long) putCounter.count() : 0L;
        long evicts = evictCounter != null ? (long) evictCounter.count() : 0L;
        return new CacheMetrics(hits, misses, puts, evicts);
    }
}
