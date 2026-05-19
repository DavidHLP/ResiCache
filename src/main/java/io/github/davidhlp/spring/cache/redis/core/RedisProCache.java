package io.github.davidhlp.spring.cache.redis.core;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

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
        this.hitCounter = Counter.builder("resicache.cache.hit")
                .tag("cache", name)
                .description("Cache hit count")
                .register(meterRegistry);
        this.missCounter = Counter.builder("resicache.cache.miss")
                .tag("cache", name)
                .description("Cache miss count")
                .register(meterRegistry);
        this.putCounter = Counter.builder("resicache.cache.put.count")
                .tag("cache", name)
                .description("Cache put count")
                .register(meterRegistry);
        this.evictCounter = Counter.builder("resicache.cache.evict.count")
                .tag("cache", name)
                .description("Cache evict count")
                .register(meterRegistry);
    }

    @Override
    public ValueWrapper get(Object key) {
        long start = System.nanoTime();
        try {
            ValueWrapper result = super.get(key);
            if (result != null) {
                hitCounter.increment();
            } else {
                missCounter.increment();
            }
            return result;
        } finally {
            getTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        long start = System.nanoTime();
        try {
            T result = super.get(key, type);
            if (result != null) {
                hitCounter.increment();
            } else {
                missCounter.increment();
            }
            return result;
        } finally {
            getTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public <T> T get(Object key, Callable<T> loader) {
        long start = System.nanoTime();
        try {
            T result = super.get(key, loader);
            if (result != null) {
                hitCounter.increment();
            } else {
                missCounter.increment();
            }
            return result;
        } catch (Exception e) {
            missCounter.increment();
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
            putCounter.increment();
        } finally {
            putTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void evict(Object key) {
        long start = System.nanoTime();
        try {
            super.evict(key);
            evictCounter.increment();
        } finally {
            evictTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void clear() {
        long start = System.nanoTime();
        try {
            super.clear();
        } finally {
            evictTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    public long getHitCount() {
        return (long) hitCounter.count();
    }

    public long getMissCount() {
        return (long) missCounter.count();
    }

    public long getPutCount() {
        return (long) putCounter.count();
    }

    public long getEvictCount() {
        return (long) evictCounter.count();
    }

    public double getHitRate() {
        long hits = getHitCount();
        long total = hits + getMissCount();
        return total > 0 ? (double) hits / total : 0.0;
    }
}
