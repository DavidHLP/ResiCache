package io.github.davidhlp.spring.cache.redis.ratelimit;

import io.github.davidhlp.spring.cache.redis.core.RedisProCache;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class RateLimiterCacheWrapper extends RedisProCache {

    private static final double DEFAULT_QPS = 1000.0;

    /**
     * Rate limiting is applied to get() and put() operations only.
     * Evict() and clear() operations bypass rate limiting intentionally,
     * as they are maintenance operations that must complete quickly.
     */

    private final RedisProCache delegate;
    private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();
    private final double defaultQps;

    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong rateLimitSkipCount = new AtomicLong();

    public RateLimiterCacheWrapper(
            RedisProCache delegate,
            String name,
            RedisCacheWriter cacheWriter,
            RedisCacheConfiguration cacheConfiguration,
            MeterRegistry meterRegistry) {
        this(delegate, name, cacheWriter, cacheConfiguration, meterRegistry, DEFAULT_QPS);
    }

    public RateLimiterCacheWrapper(
            RedisProCache delegate,
            String name,
            RedisCacheWriter cacheWriter,
            RedisCacheConfiguration cacheConfiguration,
            MeterRegistry meterRegistry,
            double defaultQps) {
        super(name, cacheWriter, cacheConfiguration, meterRegistry);
        if (delegate instanceof RateLimiterCacheWrapper) {
            throw new IllegalStateException(
                    "Circular delegation detected: RateLimiterCacheWrapper cannot wrap another RateLimiterCacheWrapper");
        }
        this.delegate = delegate;
        this.defaultQps = defaultQps;
    }

    private RateLimiter getOrCreateLimiter(String cacheName) {
        return limiters.computeIfAbsent(cacheName, name -> new RateLimiter(defaultQps));
    }

    @Override
    public <T> T get(Object key, java.util.concurrent.Callable<T> loader) {
        if (!tryAcquire()) {
            rateLimitSkipCount.incrementAndGet();
            return loadDirect(loader);
        }
        try {
            T result = delegate.get(key, loader);
            if (result != null) {
                hitCount.incrementAndGet();
            } else {
                missCount.incrementAndGet();
            }
            return result;
        } catch (Exception e) {
            missCount.incrementAndGet();
            throw new RuntimeException("Failed to load cache value for key: " + key, e);
        }
    }

    @Override
    public void put(Object key, Object value) {
        if (!tryAcquire()) {
            rateLimitSkipCount.incrementAndGet();
            return;
        }
        delegate.put(key, value);
    }

    @Override
    public void evict(Object key) {
        delegate.evict(key);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    private boolean tryAcquire() {
        String cacheName = getName();
        RateLimiter limiter = getOrCreateLimiter(cacheName);
        return limiter.tryAcquire();
    }

    private <T> T loadDirect(java.util.concurrent.Callable<T> loader) {
        try {
            return loader.call();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load cache value", e);
        }
    }

    public long getHitCount() {
        return hitCount.get();
    }

    public long getMissCount() {
        return missCount.get();
    }

    public long getRateLimitSkipCount() {
        return rateLimitSkipCount.get();
    }

    private static class RateLimiter {
        private final double qps;
        private final AtomicLong tokens;
        private final AtomicLong lastUpdate;
        private final ReentrantLock lock = new ReentrantLock();

        RateLimiter(double qps) {
            this.qps = qps;
            this.tokens = new AtomicLong((long) qps);
            this.lastUpdate = new AtomicLong(System.nanoTime());
        }

        boolean tryAcquire() {
            long now = System.nanoTime();
            lock.lock();
            try {
                long last = lastUpdate.get();
                long elapsed = now - last;
                long tokensToAdd = (long) (elapsed * qps / TimeUnit.SECONDS.toNanos(1));
                if (tokensToAdd > 0) {
                    long current = tokens.get();
                    long newTokens = Math.min((long) qps, current + tokensToAdd);
                    tokens.set(newTokens);
                    lastUpdate.set(now);
                }
                long current = tokens.get();
                if (current <= 0) {
                    return false;
                }
                tokens.set(current - 1);
                return true;
            } finally {
                lock.unlock();
            }
        }
    }
}
