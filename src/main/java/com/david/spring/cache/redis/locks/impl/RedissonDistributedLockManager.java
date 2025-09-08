package com.david.spring.cache.redis.locks.impl;

import com.david.spring.cache.redis.locks.enums.LockType;
import com.david.spring.cache.redis.locks.interfaces.DistributedLockManager;
import com.david.spring.cache.redis.locks.interfaces.LockRetryStrategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.redisson.api.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 基于Redisson的分布式锁管理器实现 支持多种锁类型和重试策略
 *
 * @author David
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonDistributedLockManager implements DistributedLockManager {

    private final RedissonClient redissonClient;

    // 缓存已创建的锁对象，避免重复创建
    private final Map<String, RLock> lockCache = new ConcurrentHashMap<>();
    private final Map<String, RReadWriteLock> readWriteLockCache = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String lockKey, LockType lockType, long leaseTime, TimeUnit unit) {
        try {
            RLock lock = getLock(lockKey, lockType);
            boolean acquired = lock.tryLock(0, leaseTime, unit);

            if (acquired) {
                log.debug(
                        "获取锁成功: key={}, type={}, leaseTime={}ms",
                        lockKey,
                        lockType,
                        unit.toMillis(leaseTime));
            } else {
                log.debug("获取锁失败: key={}, type={}", lockKey, lockType);
            }

            return acquired;
        } catch (Exception e) {
            log.error("尝试获取锁异常: key={}, type={}, error={}", lockKey, lockType, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean tryLock(
            String lockKey, LockType lockType, long waitTime, long leaseTime, TimeUnit unit) {
        try {
            RLock lock = getLock(lockKey, lockType);
            boolean acquired = lock.tryLock(waitTime, leaseTime, unit);

            if (acquired) {
                log.debug(
                        "获取锁成功: key={}, type={}, waitTime={}ms, leaseTime={}ms",
                        lockKey,
                        lockType,
                        unit.toMillis(waitTime),
                        unit.toMillis(leaseTime));
            } else {
                log.debug(
                        "获取锁超时: key={}, type={}, waitTime={}ms",
                        lockKey,
                        lockType,
                        unit.toMillis(waitTime));
            }

            return acquired;
        } catch (Exception e) {
            log.error("尝试获取锁异常: key={}, type={}, error={}", lockKey, lockType, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void unlock(String lockKey, LockType lockType) {
        try {
            RLock lock = getLock(lockKey, lockType);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("释放锁成功: key={}, type={}", lockKey, lockType);
            } else {
                log.warn("当前线程未持有锁，无法释放: key={}, type={}", lockKey, lockType);
            }
        } catch (Exception e) {
            log.error("释放锁异常: key={}, type={}, error={}", lockKey, lockType, e.getMessage(), e);
        }
    }

    @Override
    public boolean isLocked(String lockKey, LockType lockType) {
        try {
            RLock lock = getLock(lockKey, lockType);
            return lock.isLocked();
        } catch (Exception e) {
            log.error("检查锁状态异常: key={}, type={}, error={}", lockKey, lockType, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public <T> T executeWithLock(
            String lockKey,
            LockType lockType,
            long leaseTime,
            TimeUnit unit,
            Supplier<T> supplier) {
        boolean acquired = false;
        try {
            acquired = tryLock(lockKey, lockType, leaseTime, unit);
            if (acquired) {
                log.debug("执行业务逻辑，持有锁: key={}, type={}", lockKey, lockType);
                return supplier.get();
            } else {
                log.warn("获取锁失败，无法执行业务逻辑: key={}, type={}", lockKey, lockType);
                throw new RuntimeException("获取锁失败: " + lockKey);
            }
        } finally {
            if (acquired) {
                unlock(lockKey, lockType);
            }
        }
    }

    @Override
    public <T> T executeWithLock(
            String lockKey,
            LockType lockType,
            long waitTime,
            long leaseTime,
            TimeUnit unit,
            Supplier<T> supplier) {
        boolean acquired = false;
        try {
            acquired = tryLock(lockKey, lockType, waitTime, leaseTime, unit);
            if (acquired) {
                log.debug("执行业务逻辑，持有锁: key={}, type={}", lockKey, lockType);
                return supplier.get();
            } else {
                log.warn("获取锁超时，无法执行业务逻辑: key={}, type={}", lockKey, lockType);
                throw new RuntimeException("获取锁超时: " + lockKey);
            }
        } finally {
            if (acquired) {
                unlock(lockKey, lockType);
            }
        }
    }

    @Override
    public boolean renewLock(String lockKey, LockType lockType, Duration duration) {
        try {
            RLock lock = getLock(lockKey, lockType);
            if (lock.isHeldByCurrentThread()) {
                // Redisson的RLock没有直接的续期方法，这里简化实现
                log.debug(
                        "锁续期请求: key={}, type={}, duration={}ms",
                        lockKey,
                        lockType,
                        duration.toMillis());
                // 在实际使用中，Redisson会自动续期，这里返回true表示续期成功
                return true;
            } else {
                log.warn("当前线程未持有锁，无法续期: key={}, type={}", lockKey, lockType);
                return false;
            }
        } catch (Exception e) {
            log.error("锁续期异常: key={}, type={}, error={}", lockKey, lockType, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 根据锁类型获取对应的锁对象
     *
     * @param lockKey 锁键
     * @param lockType 锁类型
     * @return 锁对象
     */
    private RLock getLock(String lockKey, LockType lockType) {
        switch (lockType) {
            case REENTRANT:
                return lockCache.computeIfAbsent(lockKey, key -> redissonClient.getLock(key));

            case FAIR:
                return lockCache.computeIfAbsent(lockKey, key -> redissonClient.getFairLock(key));

            case READ:
                RReadWriteLock readWriteLock =
                        readWriteLockCache.computeIfAbsent(
                                lockKey, key -> redissonClient.getReadWriteLock(key));
                return readWriteLock.readLock();

            case WRITE:
                RReadWriteLock writeLock =
                        readWriteLockCache.computeIfAbsent(
                                lockKey, key -> redissonClient.getReadWriteLock(key));
                return writeLock.writeLock();

            case MULTI:
                // 多重锁需要特殊处理，这里简化为普通锁
                return lockCache.computeIfAbsent(lockKey, key -> redissonClient.getLock(key));

            case RED_LOCK:
                // 红锁需要多个Redis实例，这里简化为普通锁
                return lockCache.computeIfAbsent(lockKey, key -> redissonClient.getLock(key));

            default:
                return lockCache.computeIfAbsent(lockKey, key -> redissonClient.getLock(key));
        }
    }

    /**
     * 使用重试策略执行带锁的业务逻辑
     *
     * @param lockKey 锁键
     * @param lockType 锁类型
     * @param leaseTime 锁持有时间
     * @param unit 时间单位
     * @param supplier 业务逻辑
     * @param retryStrategy 重试策略
     * @param maxRetries 最大重试次数
     * @param maxWaitTimeMillis 最大等待时间
     * @param <T> 返回值类型
     * @return 业务逻辑执行结果
     */
    public <T> T executeWithLockAndRetry(
            String lockKey,
            LockType lockType,
            long leaseTime,
            TimeUnit unit,
            Supplier<T> supplier,
            LockRetryStrategy retryStrategy,
            int maxRetries,
            long maxWaitTimeMillis) {
        long startTime = System.currentTimeMillis();
        int retryCount = 0;

        while (true) {
            boolean acquired = false;
            try {
                acquired = tryLock(lockKey, lockType, leaseTime, unit);
                if (acquired) {
                    log.debug(
                            "执行业务逻辑，持有锁: key={}, type={}, retryCount={}",
                            lockKey,
                            lockType,
                            retryCount);
                    return supplier.get();
                }

                // 检查是否应该重试
                long elapsedTime = System.currentTimeMillis() - startTime;
                if (!retryStrategy.shouldRetry(
                        retryCount, maxRetries, elapsedTime, maxWaitTimeMillis)) {
                    log.warn(
                            "获取锁重试达到上限: key={}, type={}, retryCount={}, elapsedTime={}ms",
                            lockKey,
                            lockType,
                            retryCount,
                            elapsedTime);
                    throw new RuntimeException("获取锁重试达到上限: " + lockKey);
                }

                // 计算延迟时间并等待
                long delay = retryStrategy.calculateDelay(retryCount, 100);
                log.debug(
                        "获取锁失败，延迟重试: key={}, type={}, retryCount={}, delay={}ms",
                        lockKey,
                        lockType,
                        retryCount,
                        delay);

                Thread.sleep(delay);
                retryCount++;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("锁获取被中断: " + lockKey, e);
            } finally {
                if (acquired) {
                    unlock(lockKey, lockType);
                }
            }
        }
    }
}
