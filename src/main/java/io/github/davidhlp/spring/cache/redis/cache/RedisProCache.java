package io.github.davidhlp.spring.cache.redis.cache;







import io.github.davidhlp.spring.cache.redis.cache.LoaderOrchestrator.LoadOutcome;
import io.github.davidhlp.spring.cache.redis.cache.metrics.CacheMetrics;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView;
import java.util.concurrent.Callable;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;

/**
 * 缓存实例增强 — ResiCache 与 Spring {@code RedisCache} 的扩展点.
 *
 * <p>本类在 Spring {@link RedisCache} 基础上注入:
 * <ol>
 *   <li><b>Micrometer 指标</b> — 委派 {@link RedisProCacheMetricsRegistry} 统一注册 3 Timer + 4 Counter
 *       并在 override 中按业务语义记录(timing + 命中/未命中/写/淘汰计数)。{@code MeterRegistry} 缺失时
 *       全 no-op,与 Spring 默认行为一致。</li>
 *   <li><b>Loader 路径编排</b> — 委派 {@link LoaderOrchestrator} 统一处理 bloom 短路 / sync 锁 /
 *       default load 三分枝。本类仅做 callback capture(3 个 closure) + outcome switch 翻译。</li>
 *   <li><b>方法级 operation 解析</b> — 委派 {@link CacheOperationResolver} 提供 method → operation 元数据
 *       查找。</li>
 * </ol>
 *
 * <p><b>设计纪律</b>:本类不直接 import {@code Timer} / {@code Counter} / {@code MeterRegistry} —
 * 全部 metric 关注点由 {@code RedisProCacheMetricsRegistry} 承载；特性值对象仅在构造期透传 registry。
 * 唯一耦合点是构造期把 registry 透传给 registry seam,运行期本类对 Micrometer API 零依赖。
 */
public class RedisProCache extends RedisCache {

    /**
     * 指标写侧 seam — 6 个 metric 的注册 + null-safe 记录 + 快照读取全部收口在本字段。
     *
     * <p>{@code MeterRegistry} 缺失时本字段构造为空 registry(全部 6 字段为 null),record 方法全 no-op。
     */
    private final RedisProCacheMetricsRegistry metricsRegistry;

    /** 方法级策略解析器 — 仅 lookupPolicy 使用;null 时关闭元数据查找。 */
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
     *       {@code super(String, RedisCacheWriter, RedisCacheConfiguration)}</li>
     *   <li>{@code features} —— 可选特性集合(见 {@link ResiCacheFeatures};各字段 null 表示禁用)</li>
     * </ul>
     */
    RedisProCache(
            String name,
            RedisCacheWriter cacheWriter,
            RedisCacheConfiguration cacheConfiguration,
            ResiCacheFeatures features) {
        super(name, cacheWriter, cacheConfiguration);
        this.metricsRegistry = new RedisProCacheMetricsRegistry(features.getMeterRegistry(), name);
        this.operationResolver = features.getOperationResolver();
        // loader 路径编排器 build — protection 依赖 + cache-specific callbacks 一次性绑定;
        // 生产 get(key, loader) 只需传入 key/loader/operation,不再重复装配 3 个 callback。
        this.loaderOrchestrator = new LoaderOrchestrator(
                features.getBloomGate(),
                features.getSyncSupport(),
                features.getSyncLockTimeout(),
                this::deriveRedisKey,
                this::doubleCheckLookup,
                (k, v) -> put(k, v));
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
     *   <li>switch 翻译 4 态 → 路径返回 / miss 自增 / 异常翻译</li>
     * </ol>
     * RuntimeException 直接抛 / checked Exception 翻译为 RuntimeException。
     *
     * <p>3 个 callback 已在构造期绑定到 {@link LoaderOrchestrator};此处不重复组装:
     * <ul>
     *   <li>{@code redisKeyFn} → {@link #deriveRedisKey}(super.createCacheKey) — BloomGate/SyncSupport 用</li>
     *   <li>{@code doubleCheckFn} → {@link #doubleCheckLookup}(super.get) — 缓存读原语,绕过 override 不打 metrics</li>
     *   <li>{@code putAfterLoad} → {@code (k, v) -> put(k, v)} — 走 override,保留 putTimer + putCounter</li>
     * </ul>
     * sync 与非 sync 两条 loader 路径共用 orchestrator 内的同一 load 协议;差别只在
     * sync 路径把协议跑在分布式锁内。
     */
    @Override
    public <T> T get(Object key, Callable<T> loader) {
        return metricsRegistry.recordGet(() -> {
            CachePolicyView.Source operation = lookupPolicy();
            LoadOutcome<T> outcome = loaderOrchestrator.orchestrate(getName(), loader, key, operation);
            return switch (outcome) {
                case LoaderOrchestrator.BloomShortCircuited<T> ignored -> {
                    metricsRegistry.recordMiss();
                    yield null;
                }
                case LoaderOrchestrator.Loaded<T>(T value) -> value;
                case LoaderOrchestrator.LoadedWithWriteBackFailure<T>(
                        T value, Throwable ignored) -> value;
                case LoaderOrchestrator.LoadFailed<T>(Throwable cause) -> {
                    metricsRegistry.recordMiss();
                    throw translateFailure(cause, getName());
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
     * 失败异常翻译 — 把 orchestrator 透传的 {@link Throwable} 翻译为本方法契约的
     * {@link RuntimeException}:Spring 构造的 VRE(message 内嵌 raw key)重建为
     * 同型异常,key 字段以低基数 cacheName 代替(诊断可关联缓存,不泄露 raw key;
     * loader 参数置 null — Spring 7 不存储 loader,且 loader toString 不可控),
     * 保留原始 cause;其他 RuntimeException 直接抛(保留原始栈),checked Exception
     * 包装为 message 仅含 cacheName 的 {@link RuntimeException}。
     *
     * <p>包可见:单元测试直接覆盖三条翻译分支与端到端 loader 路径(无容器)。
     */
    RuntimeException translateFailure(Throwable cause, String cacheName) {
        if (cause instanceof Cache.ValueRetrievalException vre) {
            // Key-privacy contract:重建 VRE,key 位以 cacheName 代替原始 raw key,
            // 保留类型(Spring 抽象层契约)与原始 cause。
            return new Cache.ValueRetrievalException(cacheName, null, vre.getCause());
        }
        if (cause instanceof RuntimeException re) {
            return re;
        }
        // checked Exception 包装:message 只含低基数 cacheName,不含 raw key;
        // 原始 cause 保留(不吞异常、不降级为 miss)。
        return new RuntimeException("Failed to load cache value (cache=" + cacheName + ")", cause);
    }

    /**
     * 查找当前方法在本 cache 上的策略视图 —— 1 行委派。
     *
     * <p>委派 {@link CacheOperationResolver#resolve(String, CacheOperation)}:loader 路径
     * 恒为 GET 操作,故查 {@code @RedisCacheable} 命名空间。{@code operationResolver} 为 null
     * 时直接返回 null(测试场景关闭元数据查找)。
     *
     * <p>返回稳定 {@link CachePolicyView.Source} 而非内部 operation 类型:链侧需要的只是
     * 策略字段(ttl / bloom / sync / …)。
     */
    private CachePolicyView.Source lookupPolicy() {
        return operationResolver == null
                ? null
                : operationResolver.resolve(getName(), CacheOperation.GET);
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
