package io.github.davidhlp.spring.cache.redis.core;

import io.github.davidhlp.spring.cache.redis.core.holder.CacheOperationMetadataHolder;
import io.github.davidhlp.spring.cache.redis.core.writer.support.lock.SyncSupport;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.BloomSupport;
import io.github.davidhlp.spring.cache.redis.register.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
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
import java.util.concurrent.TimeUnit;

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

    public RedisProCache(
            String name,
            RedisCacheWriter cacheWriter,
            RedisCacheConfiguration cacheConfiguration,
            MeterRegistry meterRegistry) {
        this(name, cacheWriter, cacheConfiguration, meterRegistry, null, null, null);
    }

    public RedisProCache(
            String name,
            RedisCacheWriter cacheWriter,
            RedisCacheConfiguration cacheConfiguration,
            MeterRegistry meterRegistry,
            BloomSupport bloomSupport,
            RedisCacheRegister redisCacheRegister) {
        this(name, cacheWriter, cacheConfiguration, meterRegistry, bloomSupport, redisCacheRegister, null);
    }

    public RedisProCache(
            String name,
            RedisCacheWriter cacheWriter,
            RedisCacheConfiguration cacheConfiguration,
            MeterRegistry meterRegistry,
            BloomSupport bloomSupport,
            RedisCacheRegister redisCacheRegister,
            SyncSupport syncSupport) {
        super(name, cacheWriter, cacheConfiguration);
        this.getTimer = registerTimer(meterRegistry, "resicache.cache.get",
                "Time spent getting cache entries", name);
        this.putTimer = registerTimer(meterRegistry, "resicache.cache.put",
                "Time spent putting cache entries", name);
        this.evictTimer = registerTimer(meterRegistry, "resicache.cache.evict",
                "Time spent evicting cache entries", name);
        this.hitCounter = registerCounter(meterRegistry, "resicache.cache.hit",
                "Cache hit count", name);
        this.missCounter = registerCounter(meterRegistry, "resicache.cache.miss",
                "Cache miss count", name);
        this.putCounter = registerCounter(meterRegistry, "resicache.cache.put.count",
                "Cache put count", name);
        this.evictCounter = registerCounter(meterRegistry, "resicache.cache.evict.count",
                "Cache evict count", name);
        this.bloomSupport = bloomSupport;
        this.redisCacheRegister = redisCacheRegister;
        this.syncSupport = syncSupport;
    }

    private static Timer registerTimer(MeterRegistry registry, String name,
                                       String description, String cacheName) {
        if (registry == null) {
            return null;
        }
        return Timer.builder(name)
                .tag("cache", cacheName)
                .description(description)
                .register(registry);
    }

    private static Counter registerCounter(MeterRegistry registry, String name,
                                           String description, String cacheName) {
        if (registry == null) {
            return null;
        }
        return Counter.builder(name)
                .tag("cache", cacheName)
                .description(description)
                .register(registry);
    }

    private static void safeIncrement(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    private static void safeRecord(Timer timer, long duration, TimeUnit unit) {
        if (timer != null) {
            timer.record(duration, unit);
        }
    }

    @Override
    public ValueWrapper get(Object key) {
        long start = System.nanoTime();
        try {
            ValueWrapper result = super.get(key);
            if (result != null) {
                safeIncrement(hitCounter);
            } else {
                safeIncrement(missCounter);
            }
            return result;
        } finally {
            safeRecord(getTimer, System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        long start = System.nanoTime();
        try {
            T result = super.get(key, type);
            if (result != null) {
                safeIncrement(hitCounter);
            } else {
                safeIncrement(missCounter);
            }
            return result;
        } finally {
            safeRecord(getTimer, System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public <T> T get(Object key, Callable<T> loader) {
        long start = System.nanoTime();
        try {
            // Bloom Filter 短路检查：在调用 loader 之前拦截，防止缓存穿透真正到达数据源
            RedisCacheableOperation operation = lookupOperation();
            if (operation != null && operation.isUseBloomFilter()
                    && bloomSupport != null) {
                String cacheKey = createCacheKey(key);
                if (!bloomSupport.mightContain(getName(), cacheKey)) {
                    log.debug("Bloom filter rejected loader invocation: cacheName={}, key={}", getName(), cacheKey);
                    safeIncrement(missCounter);
                    return null;
                }
            }

            if (operation != null && operation.isSync() && syncSupport != null) {
                // 分布式同步模式：使用 SyncSupport 确保跨 JVM 单飞加载
                return executeSyncLoad(key, loader, operation);
            } else {
                // 默认模式：Spring 本地锁（JVM 内单飞）
                return super.get(key, loader);
            }
        } catch (Exception e) {
            safeIncrement(missCounter);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Failed to load cache value for key: " + key, e);
        } finally {
            safeRecord(getTimer, System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 查找当前方法的缓存操作元数据
     */
    private RedisCacheableOperation lookupOperation() {
        if (redisCacheRegister == null) {
            return null;
        }
        AnnotatedElementKey elementKey = CacheOperationMetadataHolder.getCurrentKey();
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
        long start = System.nanoTime();
        try {
            super.put(key, value);
            safeIncrement(putCounter);
        } finally {
            safeRecord(putTimer, System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void evict(Object key) {
        long start = System.nanoTime();
        try {
            super.evict(key);
            safeIncrement(evictCounter);
        } finally {
            safeRecord(evictTimer, System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void clear() {
        long start = System.nanoTime();
        try {
            super.clear();
        } finally {
            safeRecord(evictTimer, System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
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
