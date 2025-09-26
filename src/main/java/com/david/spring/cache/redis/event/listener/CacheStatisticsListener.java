package com.david.spring.cache.redis.event.listener;

import com.david.spring.cache.redis.event.CacheEvent;
import com.david.spring.cache.redis.event.CacheEventListener;
import com.david.spring.cache.redis.event.CacheEventType;
import com.david.spring.cache.redis.event.entity.CacheHitEvent;
import com.david.spring.cache.redis.event.entity.CacheOperationEndEvent;
import com.david.spring.cache.redis.event.entity.CachePutEvent;
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
			case CACHE_ERROR:
				stats.errorCount.incrementAndGet();
				break;
			case CACHE_OPERATION_START:
				stats.operationStartCount.incrementAndGet();
				break;
			case CACHE_OPERATION_END:
				stats.operationEndCount.incrementAndGet();
				if (event instanceof CacheOperationEndEvent endEvent) {
					stats.totalOperationTime.addAndGet(endEvent.getTotalTime());
				}
				break;
			case PRE_REFRESH_TRIGGERED:
				stats.preRefreshTriggeredCount.incrementAndGet();
				break;
			case PRE_REFRESH_COMPLETED:
				stats.preRefreshCompletedCount.incrementAndGet();
				break;
		}

		log.debug("Updated cache statistics for '{}': hits={}, misses={}, puts={}",
				cacheName, stats.hitCount.get(), stats.missCount.get(), stats.putCount.get());
	}

	@Override
	public CacheEventType[] getSupportedEventTypes() {
		return CacheEventType.values(); // 支持所有事件类型
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
		private final AtomicLong errorCount = new AtomicLong(0);
		private final AtomicLong operationStartCount = new AtomicLong(0);
		private final AtomicLong operationEndCount = new AtomicLong(0);
		private final AtomicLong preRefreshTriggeredCount = new AtomicLong(0);
		private final AtomicLong preRefreshCompletedCount = new AtomicLong(0);
		private final AtomicLong totalAccessTime = new AtomicLong(0);
		private final AtomicLong totalExecutionTime = new AtomicLong(0);
		private final AtomicLong totalOperationTime = new AtomicLong(0);

		public long getHitCount() {return hitCount.get();}

		public long getMissCount() {return missCount.get();}

		public long getPutCount() {return putCount.get();}

		public long getEvictCount() {return evictCount.get();}

		public long getClearCount() {return clearCount.get();}

		public long getExpiredCount() {return expiredCount.get();}

		public long getErrorCount() {return errorCount.get();}

		public long getOperationStartCount() {return operationStartCount.get();}

		public long getOperationEndCount() {return operationEndCount.get();}

		public long getPreRefreshTriggeredCount() {return preRefreshTriggeredCount.get();}

		public long getPreRefreshCompletedCount() {return preRefreshCompletedCount.get();}

		public long getTotalAccessTime() {return totalAccessTime.get();}

		public long getTotalExecutionTime() {return totalExecutionTime.get();}

		public long getTotalOperationTime() {return totalOperationTime.get();}

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

		public double getAverageOperationTime() {
			long ops = operationEndCount.get();
			return ops == 0 ? 0.0 : (double) totalOperationTime.get() / ops;
		}

		public double getErrorRate() {
			long total = operationEndCount.get();
			return total == 0 ? 0.0 : (double) errorCount.get() / total;
		}
	}
}