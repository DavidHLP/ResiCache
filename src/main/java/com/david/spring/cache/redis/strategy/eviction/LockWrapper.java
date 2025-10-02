package com.david.spring.cache.redis.strategy.eviction;

import lombok.Getter;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 锁包装器 - 用于在淘汰策略中管理ReentrantLock
 *
 * <p>特性：
 *
 * <ul>
 *   <li>封装 ReentrantLock，支持公平/非公平模式
 *   <li>提供淘汰判断逻辑，只淘汰未被持有的锁
 *   <li>支持锁的复用，减少对象创建开销
 * </ul>
 */
@Getter
public class LockWrapper {
    private final ReentrantLock lock;

    /** 使用非公平锁（默认） */
    public LockWrapper() {
        this(false);
    }

    /**
     * 创建锁包装器
     *
     * @param fair true=公平锁，false=非公平锁
     */
    public LockWrapper(boolean fair) {
        this.lock = new ReentrantLock(fair);
    }

    /**
     * 判断锁是否可以被淘汰
     *
     * <p>只有满足以下条件的锁才能被淘汰：
     *
     * <ul>
     *   <li>锁未被任何线程持有
     *   <li>没有线程在等待队列中
     * </ul>
     *
     * @return true=可以淘汰，false=不能淘汰（锁正在使用中）
     */
    public boolean canEvict() {
        return !lock.isLocked() && !lock.hasQueuedThreads();
    }

    /**
     * 尝试获取锁
     *
     * @return true=获取成功，false=获取失败
     */
    public boolean tryLock() {
        return lock.tryLock();
    }

    /** 释放锁 */
    public void unlock() {
        lock.unlock();
    }

    /**
     * 获取锁（阻塞）
     */
    public void lock() {
        lock.lock();
    }

    /**
     * 判断当前线程是否持有锁
     *
     * @return true=持有，false=未持有
     */
    public boolean isHeldByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }
}
