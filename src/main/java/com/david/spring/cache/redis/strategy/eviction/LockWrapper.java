package com.david.spring.cache.redis.strategy.eviction;

import lombok.Getter;

import java.util.concurrent.locks.ReentrantLock;

/** 锁包装器 用于在淘汰策略中管理ReentrantLock */
@Getter
public class LockWrapper {
    private final ReentrantLock lock;

    public LockWrapper() {
        this.lock = new ReentrantLock();
    }

    /** 判断锁是否可以被淘汰 只有未可以被……持有淘汰 */
    public boolean canEvict() {
        return !lock.isLocked() && !lock.hasQueuedThreads();
    }
}
