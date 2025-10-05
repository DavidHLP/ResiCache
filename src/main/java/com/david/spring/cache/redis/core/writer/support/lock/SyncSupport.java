package com.david.spring.cache.redis.core.writer.support.lock;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Orchestrates the two-level locking strategy (local + distributed) while only depending on lock
 * abstractions.
 */
@Slf4j
@Component
public class SyncSupport {

    private final List<LockManager> lockManagers;

    public SyncSupport(List<LockManager> lockManagers) {
        this.lockManagers = List.copyOf(lockManagers);
    }

    /**
     * Executes the loader under the protection of each configured lock manager. If any lock cannot
     * be acquired the loader is executed immediately without lock guarantees.
     */
    public <T> T executeSync(String key, Supplier<T> loader, long timeoutSeconds) {
        if (lockManagers.isEmpty()) {
            return loader.get();
        }

        Deque<LockManager.LockHandle> acquiredHandles = new ArrayDeque<>(lockManagers.size());

        try {
            for (LockManager manager : lockManagers) {
                Optional<LockManager.LockHandle> handle = manager.tryAcquire(key, timeoutSeconds);
                if (handle.isEmpty()) {
                    log.warn(
                            "Lock manager {} failed to acquire lock for key: {}, executing loader without full sync protection",
                            manager.getClass().getSimpleName(),
                            key);
                    return loader.get();
                }

                acquiredHandles.push(handle.get());
            }

            log.debug("Acquired all sync locks for cache key: {}", key);
            return loader.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for lock chain on cache key: {}", key, e);
            return loader.get();
        } finally {
            releaseLocks(acquiredHandles);
        }
    }

    private void releaseLocks(Deque<LockManager.LockHandle> handles) {
        while (!handles.isEmpty()) {
            try (LockManager.LockHandle handle = handles.pop()) {
                try {
                    handle.release();
                } catch (Exception e) {
                    log.error("Failed to release sync lock", e);
                }
            }
        }
    }
}
