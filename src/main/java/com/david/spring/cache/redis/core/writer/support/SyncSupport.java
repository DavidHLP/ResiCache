package com.david.spring.cache.redis.core.writer.support;

import com.david.spring.cache.redis.strategy.eviction.EvictionStrategy;
import com.david.spring.cache.redis.strategy.eviction.EvictionStrategyFactory;
import com.david.spring.cache.redis.strategy.eviction.LockWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/// 同步支持类，提供两级锁机制防止缓存击穿
///
/// 锁获取顺序：
///  - 本地锁（ReentrantLock）- 防止同一 JVM 内的并发请求
///  - 分布式锁（Redisson RLock）- 防止多个服务实例之间的并发请求
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncSupport {

    private final InternalLockSupport internalLockSupport;
    private final DistributedLockSupport distributedLockSupport;

    /**
     * 同步执行缓存加载操作（两级锁机制）
     *
     * @param key 缓存 key
     * @param loader 数据加载器
     * @param timeoutSeconds 锁等待超时时间(秒)
     * @param <T> 返回类型
     * @return 加载的数据
     */
    public <T> T executeSync(String key, Supplier<T> loader, long timeoutSeconds) {
        LockWrapper internalLock = null;
        RLock distributedLock = null;

        try {
            // 第一级：获取本地锁
            internalLock = internalLockSupport.tryAcquire(key, timeoutSeconds);
            if (internalLock == null) {
                log.warn(
                        "Failed to acquire internal lock for cache key within {} seconds: {}",
                        timeoutSeconds,
                        key);
                return loader.get();
            }
            log.debug("Acquired internal lock for cache key: {}", key);

            // 第二级：获取分布式锁
            distributedLock = distributedLockSupport.tryAcquire(key, timeoutSeconds);
            if (distributedLock == null) {
                log.warn(
                        "Failed to acquire distributed lock for cache key within {} seconds: {}",
                        timeoutSeconds,
                        key);
                return loader.get();
            }
            log.debug("Acquired distributed lock for cache key: {}", key);

            // 执行数据加载
            log.debug("Executing loader for cache key: {}", key);
            return loader.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for lock on cache key: {}", key, e);
            return loader.get();
        } finally {
            // 释放分布式锁（先释放外层锁）
            if (distributedLock != null) {
                distributedLockSupport.release(distributedLock, key);
            }

            // 释放本地锁
            if (internalLock != null) {
                internalLockSupport.release(internalLock, key);
            }
        }
    }
}

/// 分布式锁支持（基于 Redisson）
///
/// 使用 Redisson RLock 防止多个服务实例之间同时加载相同的缓存数据
///
/// 特性：
///
/// - 基于 Redis 的分布式锁，支持多实例部署
/// - 自动续期（看门狗机制）
/// - 可重入锁
///
@Slf4j
@Component
@RequiredArgsConstructor
class DistributedLockSupport {

    /** 分布式锁的 key 前缀 */
    private static final String LOCK_PREFIX = "cache:lock:";

    /** Redisson 客户端 */
    private final RedissonClient redissonClient;

    /**
     * 使用分布式锁执行操作
     *
     * @param key 锁的 key
     * @param loader 数据加载器
     * @param timeoutSeconds 锁等待超时时间（秒）
     * @param <T> 返回类型
     * @return 加载的数据
     */
    public <T> T executeWithLock(String key, Supplier<T> loader, long timeoutSeconds) {
        String lockKey = LOCK_PREFIX + key;
        RLock lock = redissonClient.getLock(lockKey);

        boolean acquired = false;
        try {
            // tryLock(waitTime, leaseTime, unit)
            // leaseTime = -1 表示使用看门狗机制自动续期
            acquired = lock.tryLock(timeoutSeconds, -1, TimeUnit.SECONDS);
            if (!acquired) {
                logAcquireFailure(key, timeoutSeconds);
                return loader.get();
            }

            logAcquired(key);
            return loader.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logAcquireInterrupted(key, e);
            return loader.get();
        } finally {
            if (acquired) {
                try {
                    lock.unlock();
                    logReleased(key);
                } catch (Exception e) {
                    logReleaseFailure(key, e);
                }
            }
        }
    }

    /**
     * 尝试获取分布式锁
     *
     * @param key 锁的 key
     * @param timeoutSeconds 超时时间（秒）
     * @return RLock 如果获取成功，否则返回 null
     */
    public RLock tryAcquire(String key, long timeoutSeconds) throws InterruptedException {
        String lockKey = LOCK_PREFIX + key;
        RLock lock = redissonClient.getLock(lockKey);

        boolean acquired = lock.tryLock(timeoutSeconds, -1, TimeUnit.SECONDS);
        if (acquired) {
            logAcquired(key);
            return lock;
        }

        logAcquireFailure(key, timeoutSeconds);
        return null;
    }

    /**
     * 释放分布式锁
     *
     * @param lock 锁对象
     * @param key 锁的 key（用于日志）
     */
    public void release(RLock lock, String key) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
                logReleased(key);
            } catch (Exception e) {
                logReleaseFailure(key, e);
            }
        }
    }

    // 日志方法封装

    private void logAcquireFailure(String key, long timeoutSeconds) {
        log.warn(
                "Failed to acquire distributed lock for key within {} seconds: {}",
                timeoutSeconds,
                key);
    }

    private void logAcquired(String key) {
        log.debug("Acquired distributed lock for key: {}", key);
    }

    private void logAcquireInterrupted(String key, InterruptedException e) {
        log.error("Interrupted while waiting for distributed lock on key: {}", key, e);
    }

    private void logReleased(String key) {
        log.debug("Released distributed lock for key: {}", key);
    }

    private void logReleaseFailure(String key, Exception e) {
        log.error("Failed to release distributed lock for key: {}", key, e);
    }
}

/// 本地锁支持（JVM 内部锁）
///
/// 使用 ReentrantLock 防止同一 JVM 内多个线程同时加载相同的缓存数据
///
/// 特性：
///
///  - 使用 TwoListEvictionStrategy 管理锁对象，自动淘汰未使用的锁
///  - 只淘汰未被持有的锁，避免死锁
///  - 轻量级，适合高并发场景
@Slf4j
@Component
class InternalLockSupport {

    /** 本地锁淘汰策略 */
    private final EvictionStrategy<String, LockWrapper> lockStrategy;

    public InternalLockSupport() {
        this(1024, 512);
    }

    public InternalLockSupport(int maxActiveSize, int maxInactiveSize) {
        // 创建双链表淘汰策略，只淘汰未被持有的锁
        this.lockStrategy =
                EvictionStrategyFactory.createTwoListWithPredicate(
                        maxActiveSize, maxInactiveSize, LockWrapper::canEvict);
        logInitialize(maxActiveSize, maxInactiveSize);
    }

    /**
     * 使用本地锁执行操作
     *
     * @param key 锁的 key
     * @param loader 数据加载器
     * @param timeoutSeconds 锁等待超时时间（秒）
     * @param <T> 返回类型
     * @return 加载的数据
     */
    public <T> T executeWithLock(String key, Supplier<T> loader, long timeoutSeconds) {
        LockWrapper wrapper = getOrCreateLock(key);
        ReentrantLock lock = wrapper.getLock();

        boolean acquired = false;
        try {
            acquired = lock.tryLock(timeoutSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                logAcquireFailure(key, timeoutSeconds);
                return loader.get();
            }

            logAcquireSuccess(key);
            return loader.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logAcquireInterrupted(key, e);
            return loader.get();
        } finally {
            if (acquired) {
                lock.unlock();
                logReleaseSuccess(key);
            }
        }
    }

    /**
     * 尝试获取锁
     *
     * @param key 锁的 key
     * @param timeoutSeconds 超时时间（秒）
     * @return 锁包装器，如果获取成功
     */
    public LockWrapper tryAcquire(String key, long timeoutSeconds) throws InterruptedException {
        LockWrapper wrapper = getOrCreateLock(key);
        ReentrantLock lock = wrapper.getLock();

        boolean acquired = lock.tryLock(timeoutSeconds, TimeUnit.SECONDS);
        if (acquired) {
            logAcquireSuccess(key);
            return wrapper;
        }

        logAcquireFailure(key, timeoutSeconds);
        return null;
    }

    /**
     * 释放锁
     *
     * @param wrapper 锁包装器
     */
    public void release(LockWrapper wrapper, String key) {
        if (wrapper != null) {
            try {
                wrapper.getLock().unlock();
                logReleaseSuccess(key);
            } catch (Exception e) {
                logReleaseFailure(key, e);
            }
        }
    }

    /** 获取或创建本地锁 */
    private LockWrapper getOrCreateLock(String key) {
        LockWrapper wrapper = lockStrategy.get(key);
        if (wrapper != null) {
            return wrapper;
        }

        // 创建新锁
        LockWrapper newWrapper = new LockWrapper();
        lockStrategy.put(key, newWrapper);
        logCreateLock(key, lockStrategy.getStats());
        return newWrapper;
    }

    /** 获取锁统计信息 */
    public String getStats() {
        return lockStrategy.getStats().toString();
    }

    // 日志方法化
    private void logInitialize(int maxActiveSize, int maxInactiveSize) {
        log.info(
                "Initialized InternalLockSupport with maxActiveSize={}, maxInactiveSize={}",
                maxActiveSize,
                maxInactiveSize);
    }

    private void logAcquireSuccess(String key) {
        log.debug("Acquired internal lock for key: {}", key);
    }

    private void logAcquireFailure(String key, long timeoutSeconds) {
        log.warn(
                "Failed to acquire internal lock for key within {} seconds: {}",
                timeoutSeconds,
                key);
    }

    private void logAcquireInterrupted(String key, InterruptedException e) {
        log.error("Interrupted while waiting for internal lock on key: {}", key, e);
    }

    private void logReleaseSuccess(String key) {
        log.debug("Released internal lock for key: {}", key);
    }

    private void logReleaseFailure(String key, Exception e) {
        log.error("Failed to release internal lock for key: {}", key, e);
    }

    private void logCreateLock(String key, Object stats) {
        log.debug("Created new internal lock for key: {}, stats={}", key, stats);
    }
}
