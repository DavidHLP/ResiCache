package io.github.davidhlp.spring.cache.redis.core.writer.support.lock;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
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
public class DistributedLockManager implements LockManager {

    /** 锁持有超时倍数，leaseTime = timeoutSeconds * LEASE_TIMEOUT_MULTIPLIER */
    private static final long LEASE_TIMEOUT_MULTIPLIER = 3;

    /** 最小锁持有时间（秒），确保即使 timeoutSeconds 很小也有足够的 lease time */
    private static final long MIN_LEASE_TIME_SECONDS = 10;

    /** 锁释放重试最大次数 */
    private static final int MAX_UNLOCK_RETRIES = 3;

    /** 锁释放重试间隔（毫秒） */
    private static final long UNLOCK_RETRY_INTERVAL_MS = 100;

    private final RedissonClient redissonClient;
    private final RedisProCacheProperties properties;

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
        String lockKey = properties.getSyncLock().getPrefix() + key;
        RLock lock = redissonClient.getLock(lockKey);

        // 计算合理的 lease time，确保即使持有者崩溃也能自动释放
        long leaseTimeSeconds = Math.max(
                MIN_LEASE_TIME_SECONDS,
                timeoutSeconds * LEASE_TIMEOUT_MULTIPLIER
        );

        try {
            boolean acquired = lock.tryLock(timeoutSeconds, leaseTimeSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn(
                        "Failed to acquire distributed lock for key within {} seconds: {}",
                        timeoutSeconds,
                        key);
                return Optional.empty();
            }

            log.debug("Acquired distributed lock for key: {}, leaseTime: {}s", key, leaseTimeSeconds);
            return Optional.of(new RedissonLockHandle(lock, key));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for distributed lock on key: {}", key, e);
            throw new RuntimeException("Interrupted while waiting for distributed lock on key: " + key, e);
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

            for (int attempt = 1; attempt <= MAX_UNLOCK_RETRIES; attempt++) {
                try {
                    lock.unlock();
                    log.debug("Released distributed lock for key: {} on attempt {}", key, attempt);
                    return;
                } catch (Exception e) {
                    if (attempt == MAX_UNLOCK_RETRIES) {
                        log.error("Failed to release distributed lock for key: {} after {} attempts", key, MAX_UNLOCK_RETRIES, e);
                        return;
                    }
                    log.warn("Failed to release distributed lock for key: {} on attempt {}, retrying in {}ms", key, attempt, UNLOCK_RETRY_INTERVAL_MS);
                    try {
                        Thread.sleep(UNLOCK_RETRY_INTERVAL_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Interrupted while retrying lock release for key: {}", key, ie);
                        return;
                    }
                }
            }
        }
    }
}
