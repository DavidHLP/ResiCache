package com.david.spring.cache.redis.locks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 基于 Redisson 的分布式锁简单封装。
 *
 * <p>使用方式： - 直接注入本类，调用 lock/tryLock/unlock 或 withLock 辅助方法。 - 默认使用非公平锁，可通过 getFairLock
 * 获取公平锁实例做更细控制。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLock {

    /** 锁 Key 的默认前缀，避免与业务普通 Key 冲突 */
    private static final String DEFAULT_LOCK_PREFIX = "lock:";

    private final RedissonClient redissonClient;

    private String buildKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new IllegalArgumentException("lock key must not be blank");
        }
        return rawKey.startsWith(DEFAULT_LOCK_PREFIX) ? rawKey : DEFAULT_LOCK_PREFIX + rawKey;
    }

    /** 阻塞加锁，达到 leaseTime 到期后自动释放。 */
    public void lock(String key, long leaseTime, TimeUnit unit) {
        RLock lock = redissonClient.getLock(buildKey(key));
        lock.lock(leaseTime, unit);
    }

    /** 在 waitTime 内尝试加锁，成功后在 leaseTime 到期自动释放。 */
    public boolean tryLock(String key, long waitTime, long leaseTime, TimeUnit unit) {
        RLock lock = redissonClient.getLock(buildKey(key));
        try {
            return lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 释放锁：仅当当前线程持有该锁时才执行解锁。 */
    public void unlock(String key) {
        RLock lock = redissonClient.getLock(buildKey(key));
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (IllegalMonitorStateException ex) {
            log.warn("unlock skipped, current thread not holder. key={}", buildKey(key));
        }
    }

    /** 包裹执行：阻塞加锁后执行 Supplier，并在 finally 中安全释放。 */
    public <T> T withLock(String key, long leaseTime, TimeUnit unit, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier must not be null");
        RLock lock = redissonClient.getLock(buildKey(key));
        lock.lock(leaseTime, unit);
        try {
            return supplier.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 包裹执行：阻塞加锁后执行 Runnable，并在 finally 中安全释放。 */
    public void withLock(String key, long leaseTime, TimeUnit unit, Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        RLock lock = redissonClient.getLock(buildKey(key));
        lock.lock(leaseTime, unit);
        try {
            runnable.run();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 获取一个可重入非公平锁实例，便于外部做更细控制 */
    public RLock getLock(String key) {
        return redissonClient.getLock(buildKey(key));
    }

    /** 获取一个公平锁实例 */
    public RLock getFairLock(String key) {
        return redissonClient.getFairLock(buildKey(key));
    }
}
