package com.david.spring.cache.redis.core.writer.support.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Distributed lock manager backed by Redisson. Keeps the distributed lock concerns out of SyncSupport.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
class DistributedLockManager implements LockManager {

    private static final String LOCK_PREFIX = "cache:lock:";

    private final RedissonClient redissonClient;

    @Override
    public Optional<LockHandle> tryAcquire(String key, long timeoutSeconds) throws InterruptedException {
        String lockKey = LOCK_PREFIX + key;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean acquired = lock.tryLock(timeoutSeconds, -1, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn(
                        "Failed to acquire distributed lock for key within {} seconds: {}",
                        timeoutSeconds,
                        key);
                return Optional.empty();
            }

            log.debug("Acquired distributed lock for key: {}", key);
            return Optional.of(new RedissonLockHandle(lock, key));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for distributed lock on key: {}", key, e);
            throw e;
        }
    }

    private static final class RedissonLockHandle implements LockHandle {

        private final RLock lock;
        private final String key;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private RedissonLockHandle(RLock lock, String key) {
            this.lock = lock;
            this.key = key;
        }

        @Override
        public void release() {
            if (!released.compareAndSet(false, true)) {
                return;
            }

            if (!lock.isHeldByCurrentThread()) {
                return;
            }

            try {
                lock.unlock();
                log.debug("Released distributed lock for key: {}", key);
            } catch (Exception e) {
                log.error("Failed to release distributed lock for key: {}", key, e);
            }
        }
    }
}
