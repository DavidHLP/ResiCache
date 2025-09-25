package com.david.spring.cache.redis.event.listener;

import com.david.spring.cache.redis.event.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存统计监听器
 * 收集和记录缓存使用统计信息
 */
@Slf4j
@Component
public class CacheStatisticsListener implements CacheEventListener {

    private final ConcurrentHashMap<String, CacheStats> cacheStats = new ConcurrentHashMap<>();

    @Override
    public void onCacheEvent(CacheEvent event) {
        String cacheName = event.getCacheName();
        CacheStats stats = cacheStats.computeIfAbsent(cacheName, k -> new CacheStats());

        switch (event.getEventType()) {
            case CACHE_HIT:
                stats.hitCount.incrementAndGet();
                if (event instanceof CacheHitEvent hitEvent) {
                    stats.totalAccessTime.addAndGet(hitEvent.getAccessTime());
                }
                break;
            case CACHE_MISS:
                stats.missCount.incrementAndGet();
                break;
            case CACHE_PUT:
                stats.putCount.incrementAndGet();
                if (event instanceof CachePutEvent putEvent) {
                    stats.totalExecutionTime.addAndGet(putEvent.getExecutionTime());
                }
                break;
            case CACHE_EVICT:
                stats.evictCount.incrementAndGet();
                break;
            case CACHE_CLEAR:
                stats.clearCount.incrementAndGet();
                break;
            case CACHE_EXPIRED:
                stats.expiredCount.incrementAndGet();
                break;
        }

        log.debug("Updated cache statistics for '{}': hits={}, misses={}, puts={}",
                cacheName, stats.hitCount.get(), stats.missCount.get(), stats.putCount.get());
    }

    @Override
    public CacheEventType[] getSupportedEventTypes() {
        return new CacheEventType[]{
                CacheEventType.CACHE_HIT,
                CacheEventType.CACHE_MISS,
                CacheEventType.CACHE_PUT,
                CacheEventType.CACHE_EVICT,
                CacheEventType.CACHE_CLEAR,
                CacheEventType.CACHE_EXPIRED
        };
    }

    @Override
    public int getOrder() {
        return 1; // 高优先级
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getCacheStats(String cacheName) {
        return cacheStats.get(cacheName);
    }

    /**
     * 缓存统计信息
     */
    public static class CacheStats {
        private final AtomicLong hitCount = new AtomicLong(0);
        private final AtomicLong missCount = new AtomicLong(0);
        private final AtomicLong putCount = new AtomicLong(0);
        private final AtomicLong evictCount = new AtomicLong(0);
        private final AtomicLong clearCount = new AtomicLong(0);
        private final AtomicLong expiredCount = new AtomicLong(0);
        private final AtomicLong totalAccessTime = new AtomicLong(0);
        private final AtomicLong totalExecutionTime = new AtomicLong(0);

        public long getHitCount() { return hitCount.get(); }
        public long getMissCount() { return missCount.get(); }
        public long getPutCount() { return putCount.get(); }
        public long getEvictCount() { return evictCount.get(); }
        public long getClearCount() { return clearCount.get(); }
        public long getExpiredCount() { return expiredCount.get(); }
        public long getTotalAccessTime() { return totalAccessTime.get(); }
        public long getTotalExecutionTime() { return totalExecutionTime.get(); }

        public double getHitRate() {
            long total = hitCount.get() + missCount.get();
            return total == 0 ? 0.0 : (double) hitCount.get() / total;
        }

        public double getAverageAccessTime() {
            long hits = hitCount.get();
            return hits == 0 ? 0.0 : (double) totalAccessTime.get() / hits;
        }

        public double getAverageExecutionTime() {
            long puts = putCount.get();
            return puts == 0 ? 0.0 : (double) totalExecutionTime.get() / puts;
        }
    }
}