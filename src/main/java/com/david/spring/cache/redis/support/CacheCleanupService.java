package com.david.spring.cache.redis.support;

import com.david.spring.cache.redis.config.CacheGuardProperties;
import com.david.spring.cache.redis.registry.AbstractInvocationRegistry;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import com.david.spring.cache.redis.registry.EvictInvocationRegistry;
import com.david.spring.cache.redis.registry.records.Key;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 缓存清理服务
 * 定期清理长时间未使用的锁和调用信息，防止内存泄漏
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "cache.guard.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class CacheCleanupService {

	private final CacheGuardProperties properties;
	private final CacheInvocationRegistry cacheRegistry;
	private final EvictInvocationRegistry evictRegistry;

	public CacheCleanupService(CacheGuardProperties properties,
	                           CacheInvocationRegistry cacheRegistry,
	                           EvictInvocationRegistry evictRegistry) {
		this.properties = properties;
		this.cacheRegistry = cacheRegistry;
		this.evictRegistry = evictRegistry;
	}

	/**
	 * 定期清理过期的锁和调用信息
	 */
	@Scheduled(fixedDelayString = "#{@cacheGuardProperties.cleanup.intervalMs}")
	public void cleanupExpiredEntries() {
		if (!properties.getCleanup().isEnabled()) {
			return;
		}

		long startTime = System.currentTimeMillis();
		log.debug("Starting cache cleanup task");

		try {
			int cleanedCache = cleanupRegistry(cacheRegistry, "CacheInvocation");
			int cleanedEvict = cleanupRegistry(evictRegistry, "EvictInvocation");

			long duration = System.currentTimeMillis() - startTime;
			log.info("Cache cleanup completed: cleaned {} cache entries, {} evict entries in {}ms",
					cleanedCache, cleanedEvict, duration);
		} catch (Exception e) {
			log.error("Cache cleanup failed", e);
		}
	}

	/**
	 * 清理指定注册表中的过期条目
	 */
	private int cleanupRegistry(AbstractInvocationRegistry<?> registry, String registryType) {
		try {
			// 使用反射访问私有字段
			Field invocationsField = AbstractInvocationRegistry.class.getDeclaredField("invocations");
			Field keyLocksField = AbstractInvocationRegistry.class.getDeclaredField("keyLocks");

			invocationsField.setAccessible(true);
			keyLocksField.setAccessible(true);

			@SuppressWarnings("unchecked")
			ConcurrentMap<Key, ?> invocations = (ConcurrentMap<Key, ?>) invocationsField.get(registry);
			@SuppressWarnings("unchecked")
			ConcurrentMap<Key, ReentrantLock> keyLocks = (ConcurrentMap<Key, ReentrantLock>) keyLocksField.get(registry);

			int cleanedInvocations = cleanupInvocations(invocations, registryType);
			int cleanedLocks = cleanupLocks(keyLocks, registryType);

			return cleanedInvocations + cleanedLocks;
		} catch (Exception e) {
			log.warn("Failed to cleanup {} registry", registryType, e);
			return 0;
		}
	}

	/**
	 * 清理调用信息映射
	 */
	private int cleanupInvocations(ConcurrentMap<Key, ?> invocations, String registryType) {
		long maxIdleTime = properties.getCleanup().getInvocationMaxIdleTimeMs();
		int initialSize = invocations.size();

		if (maxIdleTime <= 0) {
			return 0;
		}

		// 简化实现：根据大小判断是否需要清理
		// 实际项目中可以添加时间戳记录最后访问时间
		if (initialSize > 1000) {
			invocations.clear();
			log.debug("Cleared {} invocations from {} registry (size threshold reached)",
					initialSize, registryType);
			return initialSize;
		}

		return 0;
	}

	/**
	 * 清理锁映射
	 */
	private int cleanupLocks(ConcurrentMap<Key, ReentrantLock> keyLocks, String registryType) {
		long maxIdleTime = properties.getCleanup().getLockMaxIdleTimeMs();
		int initialSize = keyLocks.size();
		int cleaned = 0;

		if (maxIdleTime <= 0) {
			return 0;
		}

		// 清理未被持有的锁
		for (var entry : keyLocks.entrySet()) {
			ReentrantLock lock = entry.getValue();
			if (!lock.isLocked() && !lock.hasQueuedThreads()) {
				if (keyLocks.remove(entry.getKey(), lock)) {
					cleaned++;
				}
			}
		}

		log.debug("Cleaned {} unused locks from {} registry (total: {})",
				cleaned, registryType, initialSize);
		return cleaned;
	}

	/**
	 * 手动触发清理（用于测试或紧急情况）
	 */
	public void forceCleanup() {
		log.info("Force cleanup triggered");
		cleanupExpiredEntries();
	}

	/**
	 * 获取清理统计信息
	 */
	public CleanupStats getCleanupStats() {
		try {
			Field invocationsField = AbstractInvocationRegistry.class.getDeclaredField("invocations");
			Field keyLocksField = AbstractInvocationRegistry.class.getDeclaredField("keyLocks");

			invocationsField.setAccessible(true);
			keyLocksField.setAccessible(true);

			@SuppressWarnings("unchecked")
			ConcurrentMap<Key, ?> cacheInvocations = (ConcurrentMap<Key, ?>) invocationsField.get(cacheRegistry);
			@SuppressWarnings("unchecked")
			ConcurrentMap<Key, ReentrantLock> cacheLocks = (ConcurrentMap<Key, ReentrantLock>) keyLocksField.get(cacheRegistry);

			@SuppressWarnings("unchecked")
			ConcurrentMap<Key, ?> evictInvocations = (ConcurrentMap<Key, ?>) invocationsField.get(evictRegistry);
			@SuppressWarnings("unchecked")
			ConcurrentMap<Key, ReentrantLock> evictLocks = (ConcurrentMap<Key, ReentrantLock>) keyLocksField.get(evictRegistry);

			return new CleanupStats(
					cacheInvocations.size(),
					cacheLocks.size(),
					evictInvocations.size(),
					evictLocks.size()
			);
		} catch (Exception e) {
			log.warn("Failed to get cleanup stats", e);
			return new CleanupStats(0, 0, 0, 0);
		}
	}

	/**
	 * 清理统计信息
	 */
	public record CleanupStats(
			int cacheInvocations,
			int cacheLocks,
			int evictInvocations,
			int evictLocks
	) {
		public int totalEntries() {
			return cacheInvocations + cacheLocks + evictInvocations + evictLocks;
		}
	}
}