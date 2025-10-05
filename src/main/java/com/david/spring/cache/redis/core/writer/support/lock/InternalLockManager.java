package com.david.spring.cache.redis.core.writer.support.lock;

import com.david.spring.cache.redis.strategy.eviction.EvictionStrategy;
import com.david.spring.cache.redis.strategy.eviction.EvictionStrategyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JVM local lock manager backed by a small lock pool with eviction semantics.
 */
@Slf4j
@Component
@Order(0)
class InternalLockManager implements LockManager {

    private static final int DEFAULT_MAX_ACTIVE_SIZE = 1024;
    private static final int DEFAULT_MAX_INACTIVE_SIZE = 512;

    private final EvictionStrategy<String, LockReference> lockStrategy;

    public InternalLockManager() {
        this(
                EvictionStrategyFactory.createTwoListWithPredicate(
                        DEFAULT_MAX_ACTIVE_SIZE,
                        DEFAULT_MAX_INACTIVE_SIZE,
                        LockReference::canEvict));
        log.info(
                "Initialized InternalLockManager with maxActiveSize={}, maxInactiveSize={}",
                DEFAULT_MAX_ACTIVE_SIZE,
                DEFAULT_MAX_INACTIVE_SIZE);
    }

    InternalLockManager(EvictionStrategy<String, LockReference> lockStrategy) {
        this.lockStrategy = lockStrategy;
    }

    @Override
    public Optional<LockHandle> tryAcquire(String key, long timeoutSeconds) throws InterruptedException {
        LockReference reference = getOrCreateLock(key);
        ReentrantLock lock = reference.getLock();

        try {
            boolean acquired = lock.tryLock(timeoutSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn(
                        "Failed to acquire internal lock for key within {} seconds: {}",
                        timeoutSeconds,
                        key);
                return Optional.empty();
            }

            log.debug("Acquired internal lock for key: {}", key);
            return Optional.of(new InternalLockHandle(reference, key));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for internal lock on key: {}", key, e);
            throw e;
        }
    }

    private LockReference getOrCreateLock(String key) {
        LockReference reference = lockStrategy.get(key);
        if (reference != null) {
            return reference;
        }

        LockReference newReference = new LockReference();
        lockStrategy.put(key, newReference);
        log.debug("Created new internal lock for key: {}, stats={}", key, lockStrategy.getStats());
        return newReference;
    }

    private static final class InternalLockHandle implements LockHandle {

        private final LockReference reference;
        private final String key;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private InternalLockHandle(LockReference reference, String key) {
            this.reference = reference;
            this.key = key;
        }

        @Override
        public void release() {
            if (!released.compareAndSet(false, true)) {
                return;
            }

            try {
                reference.getLock().unlock();
                log.debug("Released internal lock for key: {}", key);
            } catch (Exception e) {
                log.error("Failed to release internal lock for key: {}", key, e);
            }
        }
    }

    private static final class LockReference {
        private final ReentrantLock lock = new ReentrantLock();

        private ReentrantLock getLock() {
            return lock;
        }

        private boolean canEvict() {
            return !lock.isLocked() && !lock.hasQueuedThreads();
        }
    }
}
