package com.david.spring.cache.redis.strategy.eviction;

import com.david.spring.cache.redis.strategy.eviction.impl.TwoListEvictionStrategy;

import com.david.spring.cache.redis.strategy.eviction.stats.EvictionStats;
import com.david.spring.cache.redis.strategy.eviction.stats.LockPoolStats;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class LockPoolManager {

    /** 默认最大活跃锁数量 */
    private static final int DEFAULT_MAX_ACTIVE_LOCKS = 1024;

    /** 默认最大不活跃锁数量 */
    private static final int DEFAULT_MAX_INACTIVE_LOCKS = 512;

    /** 锁淘汰策略 - 使用双链表LRU */
    private final EvictionStrategy<String, LockWrapper> lockStrategy;

    /** 锁索引映射表 - 快速定位锁 */
    private final ConcurrentHashMap<String, LockWrapper> lockIndex;

    /** 统计信息 */
    private final AtomicLong totalAcquires = new AtomicLong(0);

    private final AtomicLong totalReleases = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    public LockPoolManager() {
        this(DEFAULT_MAX_ACTIVE_LOCKS, DEFAULT_MAX_INACTIVE_LOCKS);
    }

    public LockPoolManager(int maxActiveLocks, int maxInactiveLocks) {
        this.lockIndex = new ConcurrentHashMap<>();
        this.lockStrategy =
                new TwoListEvictionStrategy<>(maxActiveLocks, maxInactiveLocks, this::canEvictLock);

        if (log.isInfoEnabled()) {
            log.info(
                    "Initialized LockPoolManager with maxActiveLocks={}, maxInactiveLocks={}, estimatedMemory={}KB",
                    maxActiveLocks,
                    maxInactiveLocks,
                    (maxActiveLocks + maxInactiveLocks) * 48 / 1024);
        }
    }

    /**
     * 获取或创建锁
     *
     * @param key 锁的键
     * @return 锁包装器
     */
    public LockWrapper acquire(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Lock key cannot be null");
        }

        totalAcquires.incrementAndGet();

        // 尝试从索引中快速获取
        LockWrapper existingLock = lockIndex.get(key);
        if (existingLock != null) {
            cacheHits.incrementAndGet();
            // 通知策略层访问，提升优先级
            lockStrategy.get(key);
            if (log.isDebugEnabled()) {
                log.debug("Lock cache hit: key={}", key);
            }
            return existingLock;
        }

        cacheMisses.incrementAndGet();

        // 创建新锁并放入池中
        LockWrapper newLock = new LockWrapper();
        lockStrategy.put(key, newLock);
        lockIndex.put(key, newLock);

        if (log.isDebugEnabled()) {
            log.debug("Created new lock: key={}", key);
        }

        return newLock;
    }

    /**
     * 释放锁（标记为可淘汰）
     *
     * @param key 锁的键
     */
    public void release(String key) {
        if (key == null) {
            return;
        }

        totalReleases.incrementAndGet();

        // 锁会在策略中自动管理，不需要主动释放
        // 只记录统计信息
        if (log.isDebugEnabled()) {
            log.debug("Lock released: key={}", key);
        }
    }

    /**
     * 移除指定的锁
     *
     * @param key 锁的键
     */
    public void remove(String key) {
        if (key == null) {
            return;
        }

        LockWrapper removed = lockIndex.remove(key);
        if (removed != null) {
            lockStrategy.remove(key);
            if (log.isDebugEnabled()) {
                log.debug("Lock removed from pool: key={}", key);
            }
        }
    }

    /** 清空锁池 */
    public void clear() {
        lockIndex.clear();
        lockStrategy.clear();

        if (log.isInfoEnabled()) {
            log.info("Lock pool cleared");
        }
    }

    /**
     * 获取锁池统计信息
     *
     * @return 统计信息
     */
    public LockPoolStats getStats() {
        EvictionStats evictionStats = lockStrategy.getStats();
        return new LockPoolStats(
                evictionStats.totalEntries(),
                evictionStats.activeEntries(),
                evictionStats.inactiveEntries(),
                evictionStats.maxActiveSize(),
                evictionStats.maxInactiveSize(),
                totalAcquires.get(),
                totalReleases.get(),
                cacheHits.get(),
                cacheMisses.get(),
                evictionStats.totalEvictions());
    }

    /**
     * 判断锁是否可以被淘汰
     *
     * @param lockWrapper 锁包装器
     * @return true=可以淘汰，false=不能淘汰
     */
    private boolean canEvictLock(LockWrapper lockWrapper) {
        if (lockWrapper == null) {
            return true;
        }

        boolean canEvict = lockWrapper.canEvict();

        if (!canEvict && log.isDebugEnabled()) {
            log.debug(
                    "Lock is protected from eviction: isLocked={}, hasQueuedThreads={}",
                    lockWrapper.getLock().isLocked(),
                    lockWrapper.getLock().hasQueuedThreads());
        }

        return canEvict;
    }

    /**
     * 获取当前锁池大小
     *
     * @return 锁池大小
     */
    public int size() {
        return lockIndex.size();
    }

    /**
     * 获取缓存命中率
     *
     * @return 命中率 (0.0 - 1.0)
     */
    public double getHitRate() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;

        if (total == 0) {
            return 0.0;
        }

        return (double) hits / total;
    }
}
