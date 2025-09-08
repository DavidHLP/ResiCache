package com.david.spring.cache.redis.locks.interfaces;

import com.david.spring.cache.redis.locks.enums.LockType;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁管理器接口
 * 提供统一的分布式锁管理功能，支持多种锁类型和重试策略
 *
 * @author David
 */
public interface DistributedLockManager {

    /**
     * 尝试获取锁
     *
     * @param lockKey 锁的键名
     * @param lockType 锁类型
     * @param leaseTime 锁持有时间
     * @param unit 时间单位
     * @return 获取成功返回true，失败返回false
     */
    boolean tryLock(String lockKey, LockType lockType, long leaseTime, TimeUnit unit);

    /**
     * 尝试获取锁，带超时
     *
     * @param lockKey 锁的键名
     * @param lockType 锁类型
     * @param waitTime 等待时间
     * @param leaseTime 锁持有时间
     * @param unit 时间单位
     * @return 获取成功返回true，失败返回false
     */
    boolean tryLock(String lockKey, LockType lockType, long waitTime, long leaseTime, TimeUnit unit);

    /**
     * 释放锁
     *
     * @param lockKey 锁的键名
     * @param lockType 锁类型
     */
    void unlock(String lockKey, LockType lockType);

    /**
     * 检查锁是否存在
     *
     * @param lockKey 锁的键名
     * @param lockType 锁类型
     * @return 锁存在返回true
     */
    boolean isLocked(String lockKey, LockType lockType);

    /**
     * 使用锁执行业务逻辑
     *
     * @param lockKey 锁的键名
     * @param lockType 锁类型
     * @param leaseTime 锁持有时间
     * @param unit 时间单位
     * @param supplier 业务逻辑
     * @param <T> 返回值类型
     * @return 业务逻辑执行结果
     */
    <T> T executeWithLock(String lockKey, LockType lockType, long leaseTime, TimeUnit unit, Supplier<T> supplier);

    /**
     * 使用锁执行业务逻辑，带重试
     *
     * @param lockKey 锁的键名
     * @param lockType 锁类型
     * @param waitTime 等待时间
     * @param leaseTime 锁持有时间
     * @param unit 时间单位
     * @param supplier 业务逻辑
     * @param <T> 返回值类型
     * @return 业务逻辑执行结果
     */
    <T> T executeWithLock(String lockKey, LockType lockType, long waitTime, long leaseTime, TimeUnit unit, Supplier<T> supplier);

    /**
     * 续期锁
     *
     * @param lockKey 锁的键名
     * @param lockType 锁类型
     * @param duration 续期时间
     * @return 续期成功返回true
     */
    boolean renewLock(String lockKey, LockType lockType, Duration duration);
}
