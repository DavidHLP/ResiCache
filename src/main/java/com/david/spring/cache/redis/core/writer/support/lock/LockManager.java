package com.david.spring.cache.redis.core.writer.support.lock;

import java.util.Optional;

/**
 * 用于获取和释放缓存级别锁的抽象，通常由分布式存储支持。
 */
public interface LockManager {

    /**
     * 尝试在超时窗口内获取给定键的锁。
     *
     * @param key            要锁定的缓存键
     * @param timeoutSeconds 等待锁的时长
     * @return 如果获取到锁则返回锁句柄，否则返回空
     * @throws InterruptedException 如果等待时当前线程被中断
     */
    Optional<LockHandle> tryAcquire(String key, long timeoutSeconds) throws InterruptedException;


    /**
     * 获取锁管理器的优先级顺序，数值越小优先级越高。
     *
     * @return 锁管理器的顺序值
     */
    int getOrder();

    /**
     * 表示一个已持有的锁，处理完成后必须释放。
     */
    interface LockHandle extends AutoCloseable {

        @Override
        void close();
    }
}
