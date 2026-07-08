package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncSupport;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomSupport;
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
    private final BloomSupport bloomSupport;
    private final CacheOperationResolver operationResolver;
    private final SyncSupport syncSupport;

    /**
     * 构造 ResiCache 实例 — Round 5 / ADR-0014 收敛后的唯一构造入口.
     *
     * <p><b>单一 seam</b>:本类是 ResiCache 与 Spring {@code RedisCache} 的扩展点,
     * 全部 7 个依赖以命名参数显式传入,无任何构造重载。调用方需传递 {@code null}
     * 表示"该特性未启用"(对应运行时 null-safe 路径,行为与原 4-重载 null 委派一致)。
     *
     * <p><b>ADR-0057 (Round 43) 收敛</b>:原 8 参构造的 {@code redisCacheRegister} +
     * {@code methodMetadataResolver} 已合并为单一 {@link CacheOperationResolver} seam —— 消除
     * {@link #lookupOperation()} 中"读 ThreadLocal key → 查 register"的 4 行镜像协议
     * 与 {@code RedisProCacheWriter#resolveOperation} 漂移风险。本类持有 1 个 deep 依赖
     * (而非 2 个浅依赖),构造参数 -1,内部 {@code lookupOperation} 退化为 1 行委派。
     *
     * <p><b>为什么不做"便利重载"</b>:
     * <ul>
     *   <li>4 个构造重载 = 4 套参数子集 = 调用方必须记住"哪个用哪个" = 接口与实现等宽
     *       (浅模块)</li>
     *   <li>测试用 {@code null} 显式禁用不使用的特性,反而比"猜重载"更清晰</li>
     *   <li>Spring 装配路径已稳定,生产仅 1 个 7 参构造,4-参重载从未被生产代码使用
     *       (仅 2 个测试使用,见 ADR-0014)</li>
     * </ul>
     *
     * <p><b>参数契约</b>:
     * <ul>
     *   <li>{@code name / cacheWriter / cacheConfiguration} —— 必传,转发给
     *       {@link RedisCache#super(String, RedisCacheWriter, RedisCacheConfiguration)}</li>
     *   <li>{@code meterRegistry} —— 可为 null(此时所有 timer/counter 为 null,
     *       {@link RedisProCacheTimers#safeIncrement} /
     *       {@link RedisProCacheTimers#timed} 静默 no-op,见 ADR-0031)</li>
     *   <li>{@code bloomSupport} —— 可为 null(关闭缓存穿透防护,GET 路径跳过 bloom 短路)</li>
     *   <li>{@code operationResolver} —— 可为 null(关闭方法级 metadata 查找,
     *       {@link #lookupOperation()} 返回 null;等价原 {@code redisCacheRegister=null} 或
     *       {@code methodMetadataResolver=null} 任一为 null 的行为)</li>
     *   <li>{@code syncSupport} —— 可为 null(关闭分布式锁,GET 走 Spring 默认本地锁)</li>
     * </ul>
     *
     * <p><b>Round 22 收敛</b>(ADR-0031):timing & counter 注册与 null-safe 调用已迁移至
     * {@link RedisProCacheTimers} 工具 seam。本类不再包含任何
     * {@code try-finally + System.nanoTime() + safeRecord} 样板;6 个公开方法通过
     * {@link RedisProCacheTimers#timed} / {@link RedisProCacheTimers#timedGet}
     * 调用,行为字节级等价。
     */
    public RedisProCache(
            String name,
            RedisCacheWriter cacheWriter,
            RedisCacheConfiguration cacheConfiguration,
            MeterRegistry meterRegistry,
            BloomSupport bloomSupport,
            CacheOperationResolver operationResolver,
            SyncSupport syncSupport) {
        super(name, cacheWriter, cacheConfiguration);
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
        this.bloomSupport = bloomSupport;
        this.operationResolver = operationResolver;
        this.syncSupport = syncSupport;
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

    @Override
    public <T> T get(Object key, Callable<T> loader) {
        // body 抛异常 → 本 timedGet 的 finally 仍按原 try-finally 语义记录计时;
        // 外层 try-catch 翻译异常 + 自增 miss,保持与原 4-层结构字节级等价。
        // ADR-0057 / C3 收敛:bloom 短路 + sync-vs-default 决策已抽为 {@link #isBloomShortCircuited}
        // + {@link #loadValue},本方法主体收窄到「lookup → bloom 守门 → 委派 loadValue」3 步。
        try {
            return RedisProCacheTimers.timedGet(getTimer, () -> {
                RedisCacheableOperation operation = lookupOperation();
                if (isBloomShortCircuited(operation, key)) {
                    return null;
                }
                return loadValue(key, loader, operation);
            });
        } catch (Exception e) {
            RedisProCacheTimers.safeIncrement(missCounter);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Failed to load cache value for key: " + key, e);
        }
    }

    /**
     * "loader 路径"双层防御之 bloom 短路检查 — ADR-0057 / C3 抽出的 deep seam.
     *
     * <p>职责(从原 {@code get(key, loader)} 内联 9 行平移,逐字保留原行为):
     * <ol>
     *   <li>任一前置条件缺失(operation 为 null / 未启用 bloom / bloomSupport 为 null) →
     *       return false(不短路,走默认路径)</li>
     *   <li>用 {@link CacheKeys#fromRedisKey} 派生 bloomKey(与链层 {@code BloomFilterHandler.add}
     *       同源,杜绝 actualKey/redisKey 漂移缺陷,ADR-0011)</li>
     *   <li>{@code bloomSupport.mightContain} 返回 false → 记 debug 日志 + 自增 miss +
     *       return true(调用方应 return null 跳过 loader)</li>
     *   <li>mightContain 返回 true → return false(不短路,继续走 sync/默认路径)</li>
     * </ol>
     *
     * <p><b>设计纪律 — 副作用</b>:本方法在 return true 分支有副作用(自增 missCounter)。
     * 这不是单纯 predicate;而是「检 + 副作用 + 短路信号」的原子单元。
     * 拆分为「纯 check + 独立 recordBloomRejection」会破坏 locality(2 调用方要记得配对),
     * 故保持单 seam。
     *
     * <p><b>package-private</b> 供单测覆盖 3 分支:
     * <ul>
     *   <li>前置条件缺失(operation null / isUseBloomFilter=false / bloomSupport=null) → false</li>
     *   <li>mightContain=false → true + miss 自增 + 日志</li>
     *   <li>mightContain=true → false</li>
     * </ul>
     *
     * <p><b>deletion test</b>:把本方法删掉、内联回 {@code get(key, loader)} → 9 行 + 3 嵌套 if
     * 重新出现,代码量相同但失去 seam 名 + 单测入口 — 复杂度上升。
     */
    boolean isBloomShortCircuited(RedisCacheableOperation operation, Object key) {
        if (operation == null || !operation.isUseBloomFilter() || bloomSupport == null) {
            return false;
        }
        // 键一致性(ADR-0011):必须用 actualKey(CacheKeys.bloomKey,与链层 add 同源),
        // 不可用 createCacheKey 的带前缀 redisKey —— 否则查的 key 永不在过滤器里(键漂移缺陷)。
        String bloomKey = CacheKeys.fromRedisKey(getName(), createCacheKey(key)).bloomKey();
        if (!bloomSupport.mightContain(getName(), bloomKey)) {
            log.debug("Bloom filter rejected loader invocation: cacheName={}, key={}", getName(), bloomKey);
            RedisProCacheTimers.safeIncrement(missCounter);
            return true;
        }
        return false;
    }

    /**
     * "loader 路径"sync vs default 决策 — ADR-0057 / C3 抽出的 deep seam.
     *
     * <p>职责(从原 {@code get(key, loader)} 内联 5 行平移,逐字保留原行为):
     * <ol>
     *   <li>operation 启用 sync 且 syncSupport 可用 → 走 {@link #executeSyncLoad}
     *       (分布式锁 + single-flight,跨 JVM 防击穿)</li>
     *   <li>否则 → 走 {@code super.get(key, loader)}(Spring 默认本地锁,JVM 内单飞)</li>
     * </ol>
     *
     * <p><b>package-private</b> 供单测覆盖 2 分支:
     * <ul>
     *   <li>sync 路径(配 mock syncSupport)→ 委派 executeSyncLoad</li>
     *   <li>默认路径(syncSupport null 或 operation 关闭 sync)→ 委派 super.get</li>
     * </ul>
     *
     * <p><b>deletion test</b>:把本方法删掉、内联回 {@code get(key, loader)} → 5 行
     * 重新出现,代码量相同但失去 seam 名 + 单测入口 — 复杂度上升。
     */
    <T> T loadValue(Object key, Callable<T> loader, RedisCacheableOperation operation) {
        if (operation != null && operation.isSync() && syncSupport != null) {
            // 分布式同步模式:使用 SyncSupport 确保跨 JVM 单飞加载
            return executeSyncLoad(key, loader, operation);
        }
        // 默认模式:Spring 本地锁(JVM 内单飞)
        return super.get(key, loader);
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

    /**
     * 使用分布式锁执行单飞加载 — ADR-0057 收敛后的委派 seam.
     *
     * <p>仅做 3 件无状态事:解析 lockKey + 解析 timeout(annotation < 0 → 默认 10s)
     * + 委派 {@link #performLockedLoad(Object, Callable)} 给 {@code syncSupport}。
     * 原 12 行内联 lambda(双重检查 + load + put + 异常翻译)已迁出,
     * 本方法退化为 thin orchestrator;单飞契约从匿名 lambda 提升为命名 seam,
     * 3 决策分支(existing-value / null-value / loader-throws)可单测。
     */
    private <T> T executeSyncLoad(Object key, Callable<T> loader, RedisCacheableOperation operation) {
        String lockKey = createCacheKey(key);
        long timeout = resolveSyncTimeout(operation);

        return syncSupport.executeSync(lockKey, () -> performLockedLoad(key, loader), timeout);
    }

    /**
     * 解析 sync 超时时间(秒) — annotation &lt; 0 → 退到 10s 默认值。
     * 抽离自 executeSyncLoad,保持该方法主体薄到 1 委派。
     */
    private long resolveSyncTimeout(RedisCacheableOperation operation) {
        long timeout = operation.getSyncTimeout();
        return timeout > 0 ? timeout : 10L;
    }

    /**
     * 持锁后单飞加载契约 — ADR-0057 抽出的 deep seam.
     *
     * <p>职责(从 executeSyncLoad 内联 lambda 平移,逐字保留原行为):
     * <ol>
     *   <li><b>double-check</b>:{@code super.get(key)} 已存在 → 直接 return 其值
     *       (走 {@code super.get} 而非 {@code lookup} 因 {@code super.get} 经
     *       {@code fromStoreValue} 将 {@code NullValue} 转回 null,完整保留
     *       null-value round-trip 语义)</li>
     *   <li><b>load + put + return</b>:cache miss → 调 {@code loader.call()},无论
     *       返回 null 与否都 {@code put} 进缓存(由 RedisCache 配置处理空值缓存),
     *       return loaded</li>
     *   <li><b>异常翻译</b>:loader 抛 checked exception → 翻译为
     *       {@link Cache.ValueRetrievalException}(Spring 抽象层契约)</li>
     * </ol>
     *
     * <p>设计纪律:
     * <ul>
     *   <li><b>package-private 而非 private</b>:直接单测入口 —
     *       {@code RedisProCacheTest} 可绕过 {@code syncSupport} 直接调,
     *       验证 3 决策分支(existing-value fast-path / null-value 缓存 / loader 异常翻译),
     *       而无需制造并发竞态。{@code executeSyncLoad} 保持 {@code private}
     *       因其单测入口已由本方法 + {@code RedisProCache} 集成测试覆盖。</li>
     *   <li><b>不返回 future / 不持状态</b>:单次执行,无 mainResult 概念 —
     *       3 决策分支各自有明确路径(return 值 / throw),无 split-knowledge 风险。</li>
     *   <li><b>super.get 而非 lookup</b>:原 lambda 注释已说明 NullValue round-trip;
     *       平移保留该决策,不擅自替换为 lookup。</li>
     * </ul>
     *
     * <p><b>deletion test</b>:把本方法删掉、内联回 {@code syncSupport.executeSync} lambda
     * → 12 行 + 3 决策 + 0 测试,代码量相同但失去 seam 名 + 单测入口 + 分支命名 — 复杂度上升。
     *
     * @param key    缓存键
     * @param loader 数据加载器(leader 在分布式锁内执行;NullValue 缓存走原路)
     * @param <T>    返回值类型
     * @return leader loader 的结果(follower 共享同一份 — 通过 {@code syncSupport} 协调)
     * @throws Cache.ValueRetrievalException 当 loader 抛 checked exception 时翻译
     */
    @SuppressWarnings("unchecked")
    <T> T performLockedLoad(Object key, Callable<T> loader) {
        // 双重检查：可能在等待锁期间其他线程已加载。
        // 使用 super.get() 而非 lookup()，因为 lookup() 返回的原始值包含 NullValue.INSTANCE，
        // 而 super.get() 会通过 fromStoreValue 将 NullValue 转换为 null 并正确返回缓存值。
        ValueWrapper existingValue = super.get(key);
        if (existingValue != null) {
            T result = (T) existingValue.get();
            return result;
        }

        // 执行加载
        try {
            T loaded = loader.call();
            // 无论 loaded 是否为 null，都执行 put，由 RedisCache 根据配置处理空值缓存
            put(key, loaded);
            return loaded;
        } catch (Exception ex) {
            throw new Cache.ValueRetrievalException(key, loader, ex);
        }
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
