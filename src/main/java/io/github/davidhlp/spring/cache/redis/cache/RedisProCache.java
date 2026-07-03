package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.MethodMetadataResolver;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncSupport;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomSupport;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.Cache;
import org.springframework.context.expression.AnnotatedElementKey;
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
    private final RedisCacheRegister redisCacheRegister;
    private final SyncSupport syncSupport;
    private final MethodMetadataResolver methodMetadataResolver;

    /**
     * 构造 ResiCache 实例 — Round 5 / ADR-0014 收敛后的唯一构造入口.
     *
     * <p><b>单一 seam</b>:本类是 ResiCache 与 Spring {@code RedisCache} 的扩展点,
     * 全部 8 个依赖以命名参数显式传入,无任何构造重载。调用方需传递 {@code null}
     * 表示"该特性未启用"(对应运行时 null-safe 路径,行为与原 4-重载 null 委派一致)。
     *
     * <p><b>为什么不做"便利重载"</b>:
     * <ul>
     *   <li>4 个构造重载 = 4 套参数子集 = 调用方必须记住"哪个用哪个" = 接口与实现等宽
     *       (浅模块)</li>
     *   <li>测试用 {@code null} 显式禁用不使用的特性,反而比"猜重载"更清晰</li>
     *   <li>Spring 装配路径已稳定,生产仅 1 个 8 参构造,4-参重载从未被生产代码使用
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
     *   <li>{@code redisCacheRegister} —— 可为 null(关闭方法级 metadata 查找,
     *       {@link #lookupOperation()} 返回 null)</li>
     *   <li>{@code syncSupport} —— 可为 null(关闭分布式锁,GET 走 Spring 默认本地锁)</li>
     *   <li>{@code methodMetadataResolver} —— 可为 null(关闭 ThreadLocal 路径,
     *       与 {@code redisCacheRegister} 协同)</li>
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
            RedisCacheRegister redisCacheRegister,
            SyncSupport syncSupport,
            MethodMetadataResolver methodMetadataResolver) {
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
        this.redisCacheRegister = redisCacheRegister;
        this.syncSupport = syncSupport;
        this.methodMetadataResolver = methodMetadataResolver;
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
        try {
            return RedisProCacheTimers.timedGet(getTimer, () -> {
                RedisCacheableOperation operation = lookupOperation();

                // Bloom Filter 短路检查(仅 sync 路径;Spring 仅对 sync=true 调 get(key,loader)):
                // 在调用 loader 之前拦截,防止缓存穿透真正到达数据源。属 C4 裁定的有意双层防御
                // (本处防 loader/数据源;链层 BloomFilterHandler 防 Redis GET),ADR-0011 不移除。
                // 键一致性(ADR-0011):必须用 actualKey(CacheKeys.bloomKey,与链层 add 同源),
                // 不可用 createCacheKey 的带前缀 redisKey —— 否则查的 key 永不在过滤器里(键漂移缺陷)。
                if (operation != null && operation.isUseBloomFilter()
                        && bloomSupport != null) {
                    String bloomKey = CacheKeys.fromRedisKey(getName(), createCacheKey(key)).bloomKey();
                    if (!bloomSupport.mightContain(getName(), bloomKey)) {
                        log.debug("Bloom filter rejected loader invocation: cacheName={}, key={}", getName(), bloomKey);
                        RedisProCacheTimers.safeIncrement(missCounter);
                        return null;
                    }
                }

                if (operation != null && operation.isSync() && syncSupport != null) {
                    // 分布式同步模式:使用 SyncSupport 确保跨 JVM 单飞加载
                    return executeSyncLoad(key, loader, operation);
                } else {
                    // 默认模式:Spring 本地锁(JVM 内单飞)
                    return super.get(key, loader);
                }
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
     * 查找当前方法的缓存操作元数据
     */
    private RedisCacheableOperation lookupOperation() {
        if (redisCacheRegister == null) {
            return null;
        }
        // Path C Step 1: 从 MethodMetadataResolver 读取,不再直接访问静态 holder
        AnnotatedElementKey elementKey = methodMetadataResolver == null
                ? null : methodMetadataResolver.currentKey();
        if (elementKey == null) {
            return null;
        }
        return redisCacheRegister.getCacheableOperation(getName(), elementKey);
    }

    /**
     * 使用分布式锁执行单飞加载
     *
     * <p>逻辑：在分布式锁内双重检查缓存 → 加载 → 写入 → 返回。
     * 确保同一 key 在分布式环境下只有一个 JVM 会调用 loader。
     */
    @SuppressWarnings("unchecked")
    private <T> T executeSyncLoad(Object key, Callable<T> loader, RedisCacheableOperation operation) {
        String lockKey = createCacheKey(key);
        long timeout = operation.getSyncTimeout();
        if (timeout <= 0) {
            timeout = 10;
        }

        return syncSupport.executeSync(lockKey, () -> {
            // 双重检查：可能在等待锁期间其他线程已加载。
            // 使用 super.get() 而非 lookup()，因为 lookup() 返回的原始值包含 NullValue.INSTANCE，
            // 而 super.get() 会通过 fromStoreValue 将 NullValue 转换为 null 并正确返回缓存值。
            ValueWrapper existingValue = super.get(key);
            if (existingValue != null) {
                @SuppressWarnings("unchecked")
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
        }, timeout);
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

    public long getHitCount() {
        return hitCounter != null ? (long) hitCounter.count() : 0L;
    }

    public long getMissCount() {
        return missCounter != null ? (long) missCounter.count() : 0L;
    }

    public long getPutCount() {
        return putCounter != null ? (long) putCounter.count() : 0L;
    }

    public long getEvictCount() {
        return evictCounter != null ? (long) evictCounter.count() : 0L;
    }

    public double getHitRate() {
        long hits = getHitCount();
        long total = hits + getMissCount();
        return total > 0 ? (double) hits / total : 0.0;
    }
}
