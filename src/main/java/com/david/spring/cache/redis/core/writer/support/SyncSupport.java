package com.david.spring.cache.redis.core.writer.support;

import com.david.spring.cache.redis.strategy.eviction.EvictionStrategy;
import com.david.spring.cache.redis.strategy.eviction.EvictionStrategyFactory;
import com.david.spring.cache.redis.strategy.eviction.LockWrapper;
import com.david.spring.cache.redis.strategy.eviction.TwoListEvictionStrategy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** 同步加载支持类V2 使用通用淘汰策略管理锁,防止内存占用过多 */
@Slf4j
@Component
public class SyncSupport {
    /** 默认锁等待超时时间(秒) */
    private static final long DEFAULT_LOCK_TIMEOUT = 10;

    /** 锁淘汰策略 */
    private final EvictionStrategy<String, LockWrapper> lockStrategy;

    public SyncSupport() {
        this(1024, 512);
    }

    public SyncSupport(int maxActiveSize, int maxInactiveSize) {
        // 创建双链表淘汰策略,只淘汰未被持有的锁
        this.lockStrategy =
                EvictionStrategyFactory.createTwoListWithPredicate(
                        maxActiveSize, maxInactiveSize, LockWrapper::canEvict);
    }

    /**
     * 同步执行缓存加载操作
     *
     * @param key 缓存key
     * @param loader 数据加载器
     * @param <T> 返回类型
     * @return 加载的数据
     */
    public <T> T executeSync(String key, Supplier<T> loader) {
        return executeSync(key, loader, DEFAULT_LOCK_TIMEOUT);
    }

    /**
     * 同步执行缓存加载操作
     *
     * @param key 缓存key
     * @param loader 数据加载器
     * @param timeoutSeconds 锁等待超时时间(秒)
     * @param <T> 返回类型
     * @return 加载的数据
     */
    public <T> T executeSync(String key, Supplier<T> loader, long timeoutSeconds) {
        LockWrapper wrapper = getOrCreateLock(key);
        ReentrantLock lock = wrapper.getLock();

        boolean acquired = false;
        try {
            acquired = lock.tryLock(timeoutSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn(
                        "Failed to acquire lock for cache key within {} seconds: {}",
                        timeoutSeconds,
                        key);
                return loader.get();
            }

            log.debug("Acquired lock for cache key: {}", key);
            return loader.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for lock on cache key: {}", key, e);
            return loader.get();
        } finally {
            if (acquired) {
                lock.unlock();
                log.debug("Released lock for cache key: {}", key);
            }
        }
    }

    /** 获取或创建锁 */
    private LockWrapper getOrCreateLock(String key) {
        LockWrapper wrapper = lockStrategy.get(key);
        if (wrapper != null) {
            return wrapper;
        }

        // 创建新锁
        LockWrapper newWrapper = new LockWrapper();
        lockStrategy.put(key, newWrapper);
        log.debug("Created new lock for key: {}, stats={}", key, lockStrategy.getStats());
        return newWrapper;
    }

    /** 判断指定key是否正在被同步加载 */
    public boolean isLocked(String key) {
        LockWrapper wrapper = lockStrategy.get(key);
        return wrapper != null && wrapper.getLock().isLocked();
    }

    /** 获取当前锁数量 */
    public int getLockMapSize() {
        return lockStrategy.size();
    }

    /** 获取统计信息 */
    public String getStats() {
        return lockStrategy.getStats().toString();
    }

    /** 清理所有锁(测试用) */
    protected void clearLocks() {
        lockStrategy.clear();
        log.debug("Cleared all locks");
    }
}
