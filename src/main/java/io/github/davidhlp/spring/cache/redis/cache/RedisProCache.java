package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.cache.loader.CacheOperationResolver;
import io.github.davidhlp.spring.cache.redis.cache.loader.LoaderOrchestrator;
import io.github.davidhlp.spring.cache.redis.cache.metrics.CacheMetrics;
import io.github.davidhlp.spring.cache.redis.cache.metrics.RedisProCacheMetricsRegistry;
import io.github.davidhlp.spring.cache.redis.cache.model.ResiCacheFeatures;
import io.github.davidhlp.spring.cache.redis.cache.loader.LoaderOrchestrator.LoadOutcome;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.util.concurrent.Callable;

/**
 * 缓存实例增强 — ResiCache 与 Spring {@code RedisCache} 的扩展点.
 *
 * <p>本类在 Spring {@link RedisCache} 基础上注入:
 * <ol>
 *   <li><b>Micrometer 指标</b> — 委派 {@link RedisProCacheMetricsRegistry} 统一注册 3 Timer + 4 Counter
 *       并在 override 中按业务语义记录(timing + 命中/未命中/写/淘汰计数)。{@code MeterRegistry} 缺失时
 *       全 no-op,与 Spring 默认行为一致。</li>
 *   <li><b>Loader 路径编排</b> — 委派 {@link LoaderOrchestrator} 统一处理 bloom 短路 / sync 锁 /
 *       default load 三分枝。本类仅做 callback capture(4 个 closure) + outcome switch 翻译。</li>
 *   <li><b>方法级 operation 解析</b> — 委派 {@link CacheOperationResolver} 提供 method → operation 元数据
 *       查找。</li>
 * </ol>
 *
 * <p><b>设计纪律</b>:本类不直接 import {@code Timer} / {@code Counter} / {@code MeterRegistry} —
 * 全部 metric 关注点由 {@link RedisProCacheMetricsRegistry} 承载。{@link ResiCacheFeatures#getMeterRegistry()}
 * 唯一耦合点是构造期把 registry 透传给 registry seam,运行期本类对 Micrometer API 零依赖。
 */
@Slf4j
public class RedisProCache extends RedisCache {

    /**
     * 指标写侧 seam — 6 个 metric 的注册 + null-safe 记录 + 快照读取全部收口在本字段。
     *
     * <p>{@code MeterRegistry} 缺失时本字段构造为空 registry(全部 6 字段为 null),record 方法全 no-op。
     */
    private final RedisProCacheMetricsRegistry metricsRegistry;

    /** 方法级 operation 元数据解析器 — 仅 lookupOperation 使用;null 时关闭元数据查找。 */
    private final CacheOperationResolver operationResolver;

    /**
     * Loader 路径编排器 — loader-path deep seam。
     *
     * <p>{@code bloomGate} / {@code syncSupport} / {@code syncLockTimeout} 3 个 protection
     * 协作 bean 全部由 {@link LoaderOrchestrator} 持有,本类在构造期一次性 build 后委派
     * {@link LoaderOrchestrator#orchestrate}。
     *
     * <p>设计纪律:orchestrator 不持有本类引用,委派通过回调实现 —
     * {@link #put} 闭包(preserve metrics)+ {@code super.get}/{@code super.get(key, loader)}
     * (via 参数 {@code this})。
     */
    private final LoaderOrchestrator loaderOrchestrator;

    /**
     * 构造 ResiCache 实例 — 唯一构造入口。
     *
     * <p><b>单一 seam</b>:本类是 ResiCache 与 Spring {@code RedisCache} 的扩展点。
     * 全部可选特性收口到单一 {@link ResiCacheFeatures} 值对象,「null = 该特性禁用」的契约
     * 只存在于 {@link ResiCacheFeatures} 一处。测试用 {@link ResiCacheFeatures#none()} 或
     * builder 显式声明启用的特性。
     *
     * <p>构造期委派 3 个 deep seam:
     * <ol>
     *   <li>{@link RedisProCacheMetricsRegistry} — 6 metric 注册</li>
     *   <li>{@link CacheOperationResolver} — operation 解析</li>
     *   <li>{@link LoaderOrchestrator} — loader 路径编排</li>
     * </ol>
     *
     * <p><b>参数契约</b>:
     * <ul>
     *   <li>{@code name / cacheWriter / cacheConfiguration} —— 必传,转发给
     *       {@link RedisCache#super(String, RedisCacheWriter, RedisCacheConfiguration)}</li>
     *   <li>{@code features} —— 可选特性集合(见 {@link ResiCacheFeatures};各字段 null 表示禁用)</li>
     * </ul>
     */
    public RedisProCache(
            String name,
            RedisCacheWriter cacheWriter,
            RedisCacheConfiguration cacheConfiguration,
            ResiCacheFeatures features) {
        super(name, cacheWriter, cacheConfiguration);
        this.metricsRegistry = new RedisProCacheMetricsRegistry(features.getMeterRegistry(), name);
        this.operationResolver = features.getOperationResolver();
        // loader 路径编排器 build — 委派 bloomGate/syncSupport/syncLockTimeout + 1 putAfterLoad 闭包;
        // orchestrator 与本类解耦,通过闭包 + super 引用完成 cache-specific 操作。
        this.loaderOrchestrator = new LoaderOrchestrator(
                features.getBloomGate(),
                features.getSyncSupport(),
                features.getSyncLockTimeout());
    }

    @Override
    public ValueWrapper get(Object key) {
        return metricsRegistry.recordGet(() -> {
            ValueWrapper result = super.get(key);
            if (result != null) {
                metricsRegistry.recordHit();
            } else {
                metricsRegistry.recordMiss();
            }
            return result;
        });
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        return metricsRegistry.recordGet(() -> {
            T result = super.get(key, type);
            if (result != null) {
                metricsRegistry.recordHit();
            } else {
                metricsRegistry.recordMiss();
            }
            return result;
        });
    }

    /**
     * Loader 路径主入口 — 编排逻辑(bloom 短路 / sync vs default 调度 / locked-load 主体)
     * 由 {@link LoaderOrchestrator#orchestrate} 承担,本方法:
     * <ol>
     *   <li>timed wrap(getTimer)(委派 {@link RedisProCacheMetricsRegistry#recordGet})</li>
     *   <li>委派 orchestrator.orchestrate(...) 返回 {@link LoadOutcome}</li>
     *   <li>switch 翻译 3 态 → 路径返回 / miss 自增 / 异常翻译</li>
     * </ol>
     * miss counter 自增:bloom 短路 1 次 / 失败路径 1 次 / 成功路径 0 次;异常翻译:
     * RuntimeException 直接抛 / checked Exception 翻译为 RuntimeException。
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
        return metricsRegistry.recordGet(() -> {
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
                    metricsRegistry.recordMiss();
                    yield null;
                }
                case LoaderOrchestrator.Loaded<T>(T value) -> value;
                case LoaderOrchestrator.LoadFailed<T>(Throwable cause) -> {
                    metricsRegistry.recordMiss();
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
     * 查找当前方法的缓存操作元数据 —— 1 行委派。
     *
     * <p>委派 {@link CacheOperationResolver#resolve(String)}。{@code operationResolver}
     * 为 null 时直接返回 null(测试场景关闭元数据查找)。
     */
    private RedisCacheableOperation lookupOperation() {
        return operationResolver == null ? null : operationResolver.resolve(getName());
    }

    @Override
    public void put(Object key, Object value) {
        metricsRegistry.recordPut(() -> super.put(key, value));
    }

    @Override
    public void evict(Object key) {
        metricsRegistry.recordEvict(() -> super.evict(key));
    }

    @Override
    public void clear() {
        metricsRegistry.recordClear(super::clear);
    }

    /**
     * 当前缓存实例的指标快照。
     *
     * <p>委派 {@link RedisProCacheMetricsRegistry#metrics()} 读取,本方法仅做 1 行委派 —
     * 全部 4 个 Counter 字段的 null-safe 读取收口在 registry seam 内。{@link CacheMetrics} 派生
     * 指标 {@code hitRate} 由 record 内集中计算。
     *
     * <p>Spring Boot Actuator 与 Micrometer Timer/Counter 注册不受影响
     * (本方法只读,不重置),外部观测不破坏。
     *
     * @return 当前缓存实例的指标快照(不可变)
     */
    public CacheMetrics metrics() {
        return metricsRegistry.metrics();
    }
}
