package com.david.spring.cache.redis.core.writer.support.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Handles sync execution by first coordinating threads inside the JVM, then escalating to
 * distributed locks when available.
 */
@Slf4j
@Component
public class SyncSupport {

	private final List<LockManager> distributedManagers;
	private final ConcurrentMap<String, Object> localMonitors = new ConcurrentHashMap<>();

	public SyncSupport(List<LockManager> lockManagers) {
		this.distributedManagers = List.copyOf(lockManagers);
	}

	public <T> T executeSync(String key, Supplier<T> loader, long timeoutSeconds) {
		Object monitor = localMonitors.computeIfAbsent(key, k -> new Object());
		synchronized (monitor) {
			if (distributedManagers.isEmpty()) {
				return loader.get();
			}

			Deque<LockManager.LockHandle> distributedHandles =
					new ArrayDeque<>(distributedManagers.size());
			try {
				for (LockManager manager : distributedManagers) {
					Optional<LockManager.LockHandle> handle =
							manager.tryAcquire(key, timeoutSeconds);
					if (handle.isEmpty()) {
						log.warn(
								"Lock manager {} failed to acquire distributed lock for key: {}",
								manager.getClass().getSimpleName(),
								key);
						return loader.get();
					}
					distributedHandles.push(handle.get());
				}

				log.debug(
						"Acquired distributed lock(s) for cache key: {} (count={})",
						key,
						distributedHandles.size());

				return loader.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.error("Interrupted while acquiring distributed lock for key: {}", key, e);
				return loader.get();
			} finally {
				releaseLocks(distributedHandles);
				localMonitors.remove(key, monitor);
			}
		}
	}

	private void releaseLocks(Deque<LockManager.LockHandle> handles) {
		while (!handles.isEmpty()) {
			try (LockManager.LockHandle handle = handles.pop()) {
				handle.release();
			} catch (Exception e) {
				log.error("Failed to release distributed lock", e);
			}
		}
	}
}
