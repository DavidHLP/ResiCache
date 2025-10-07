package com.david.spring.cache.redis.core.writer.support.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Redisson 的分布式锁管理器。将分布式锁相关的逻辑从 SyncSupport 中分离出来。
 */
@Slf4j
@Component("distributedLockManager")
@RequiredArgsConstructor
class DistributedLockManager implements LockManager {

    private static final String LOCK_PREFIX = "cache:lock:";

    private final RedissonClient redissonClient;

    /**
     * 尝试获取指定键的分布式锁
     *
     * @param key            锁的键名
     * @param timeoutSeconds 获取锁的超时时间（秒）
     * @return 如果成功获取锁则返回包含锁句柄的Optional，否则返回空的Optional
     * @throws InterruptedException 如果等待锁的过程中线程被中断
     */
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

    @Override
    public int getOrder() {
        return 0;
    }

    /**
     * 基于Redisson的锁句柄实现
     */
    private static final class RedissonLockHandle implements LockHandle {

        private final RLock lock;
        private final String key;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private RedissonLockHandle(RLock lock, String key) {
            this.lock = lock;
            this.key = key;
        }

        /**
         * 释放分布式锁
         * 只有持有锁的线程才能释放锁，且每个锁只能被释放一次
         */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
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
