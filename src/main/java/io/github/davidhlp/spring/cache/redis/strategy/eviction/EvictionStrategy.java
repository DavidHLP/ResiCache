package io.github.davidhlp.spring.cache.redis.strategy.eviction;

import io.github.davidhlp.spring.cache.redis.strategy.eviction.stats.EvictionStats;

/**
 * 淘汰策略接口
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public interface EvictionStrategy<K, V> {

    /**
     * 添加或更新元素
     *
     * @param key 键
     * @param value 值
     */
    void put(K key, V value);

    /**
     * 获取元素
     *
     * @param key 键
     * @return 值,不存在返回null
     */
    V get(K key);

    /**
     * 移除元素
     *
     * @param key 键
     * @return 被移除的值,不存在返回null
     */
    V remove(K key);

    /**
     * 判断元素是否存在
     *
     * @param key 键
     * @return true表示存在
     */
    boolean contains(K key);

    /**
     * 获取当前元素数量
     *
     * @return 元素数量
     */
    int size();

    /**
     * 清空所有元素
     */
    void clear();

    /**
     * 获取统计信息
     *
     * @return 统计信息
     */
    EvictionStats getStats();
}
