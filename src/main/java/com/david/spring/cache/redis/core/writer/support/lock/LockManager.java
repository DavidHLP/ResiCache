package com.david.spring.cache.redis.core.writer.support.lock;

import java.util.Optional;

/**
 * Abstraction for acquiring and releasing a cache level lock.
 * Implementations encapsulate the locking mechanism so callers only deal with the contract.
 */
public interface LockManager {

    /**
     * Tries to acquire a lock for the given key within the timeout window.
     *
     * @param key cache key to lock
     * @param timeoutSeconds how long to wait for the lock
     * @return lock handle if acquired, empty otherwise
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    Optional<LockHandle> tryAcquire(String key, long timeoutSeconds) throws InterruptedException;

    /**
     * Represents a held lock that must be released when processing completes.
     */
    interface LockHandle extends AutoCloseable {

        /** Release the underlying lock. */
        void release();

        @Override
        default void close() {
            release();
        }
    }
}