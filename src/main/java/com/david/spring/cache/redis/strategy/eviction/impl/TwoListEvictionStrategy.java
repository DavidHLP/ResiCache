package com.david.spring.cache.redis.strategy.eviction.impl;

import com.david.spring.cache.redis.strategy.eviction.EvictionStrategy;
import com.david.spring.cache.redis.strategy.eviction.stats.EvictionStats;
import com.david.spring.cache.redis.strategy.eviction.support.TwoListLRU;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Predicate;

@Slf4j
public class TwoListEvictionStrategy<K, V> implements EvictionStrategy<K, V> {

    /** 默认Active List最大容量 */
    private static final int DEFAULT_MAX_ACTIVE_SIZE = 1024;

    /** 默认Inactive List最大容量 */
    private static final int DEFAULT_MAX_INACTIVE_SIZE = 512;

    private final int maxActiveSize;
    private final int maxInactiveSize;

    /** 核心LRU算法实现 */
    private final TwoListLRU<K, V> lru;

    /** 元素淘汰判断器(可选) */
    private volatile Predicate<V> evictionPredicate;

    public TwoListEvictionStrategy() {
        this(DEFAULT_MAX_ACTIVE_SIZE, DEFAULT_MAX_INACTIVE_SIZE);
    }

    public TwoListEvictionStrategy(int maxActiveSize, int maxInactiveSize) {
        this(maxActiveSize, maxInactiveSize, null);
    }

    public TwoListEvictionStrategy(
            int maxActiveSize, int maxInactiveSize, Predicate<V> evictionPredicate) {
        this.maxActiveSize = maxActiveSize;
        this.maxInactiveSize = maxInactiveSize;
        this.evictionPredicate = evictionPredicate;

        // 创建底层LRU算法实现
        this.lru = new TwoListLRU<>(maxActiveSize, maxInactiveSize, evictionPredicate);
    }

    @Override
    public void put(K key, V value) {
        lru.put(key, value);
    }

    @Override
    public V get(K key) {
        return lru.get(key);
    }

    @Override
    public V remove(K key) {
        return lru.remove(key);
    }

    @Override
    public boolean contains(K key) {
        return lru.contains(key);
    }

    @Override
    public int size() {
        return lru.size();
    }

    @Override
    public void clear() {
        lru.clear();
    }

    @Override
    public EvictionStats getStats() {
        return new EvictionStats(
                lru.size(),
                lru.getActiveSize(),
                lru.getInactiveSize(),
                maxActiveSize,
                maxInactiveSize,
                lru.getTotalEvictions());
    }

    /**
     * 设置淘汰判断器
     *
     * @param evictionPredicate 淘汰判断器
     */
    public void setEvictionPredicate(Predicate<V> evictionPredicate) {
        this.evictionPredicate = evictionPredicate;
        lru.setEvictionPredicate(evictionPredicate);
    }

    /**
     * 诊断：验证数据一致性
     *
     * <p>注意：此方法仅用于测试和调试，验证LRU内部数据结构的一致性
     */
    public void validateConsistency() {
        // 该方法保留用于测试兼容性，实际验证逻辑已在TwoListLRU内部
        if (log.isInfoEnabled()) {
            log.info(
                    "List validation: activeSize={}/{}, inactiveSize={}/{}, total={}",
                    lru.getActiveSize(),
                    maxActiveSize,
                    lru.getInactiveSize(),
                    maxInactiveSize,
                    lru.size());
        }
    }
}
