package io.github.davidhlp.spring.cache.redis.core;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class RedisProCache extends RedisCache {

    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong size = new AtomicLong();
    private final Timer getTimer;
    private final Timer putTimer;
    private final Timer evictTimer;

    public RedisProCache(
            String name,
            RedisCacheWriter cacheWriter,
            RedisCacheConfiguration cacheConfiguration,
            MeterRegistry meterRegistry) {
        super(name, cacheWriter, cacheConfiguration);
        this.getTimer = Timer.builder("resicache.cache.get")
                .tag("cache", name)
                .description("Time spent getting cache entries")
                .register(meterRegistry);
        this.putTimer = Timer.builder("resicache.cache.put")
                .tag("cache", name)
                .description("Time spent putting cache entries")
                .register(meterRegistry);
        this.evictTimer = Timer.builder("resicache.cache.evict")
                .tag("cache", name)
                .description("Time spent evicting cache entries")
                .register(meterRegistry);
    }

    @Override
    public <T> T get(Object key, java.util.concurrent.Callable<T> loader) {
        long start = System.nanoTime();
        try {
            T result = super.get(key, loader);
            if (result != null) {
                hitCount.incrementAndGet();
            } else {
                missCount.incrementAndGet();
            }
            return result;
        } catch (Exception e) {
            missCount.incrementAndGet();
            throw new RuntimeException("Failed to load cache value for key: " + key, e);
        } finally {
            getTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void put(Object key, Object value) {
        long start = System.nanoTime();
        try {
            super.put(key, value);
            // 注意：这里不维护 size 计数器，因为 put 可能更新已存在的 key，
            // 而我们无法在不加锁的情况下准确判断 key 是否已存在。
            // size 只在 evict 时递减，在 clear 时重置，能够反映缓存变化的趋势但不会精确。
        } finally {
            putTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void evict(Object key) {
        long start = System.nanoTime();
        try {
            super.evict(key);
            // size 不会变成负数（evict 已存在的 key 时才会递减）
            if (size.get() > 0) {
                size.decrementAndGet();
            }
        } finally {
            evictTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void clear() {
        try {
            super.clear();
        } finally {
            size.set(0);
        }
    }

    public long getHitCount() {
        return hitCount.get();
    }

    public long getMissCount() {
        return missCount.get();
    }

    public long getSize() {
        return size.get();
    }

    public double getHitRate() {
        long hits = hitCount.get();
        long total = hits + missCount.get();
        return total > 0 ? (double) hits / total : 0.0;
    }
}