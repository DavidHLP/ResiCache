package io.github.davidhlp.spring.cache.redis.strategy.eviction;

import io.github.davidhlp.spring.cache.redis.strategy.eviction.impl.TwoListEvictionStrategy;

import java.util.function.Predicate;

/** 淘汰策略工厂 */
public class EvictionStrategyFactory {

    /**
     * 创建淘汰策略
     *
     * @param type 策略类型
     * @param maxSize 最大容量
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 淘汰策略实例
     */
    public static <K, V> EvictionStrategy<K, V> create(StrategyType type, int maxSize) {
        return switch (type) {
            case TWO_LIST ->
                    new TwoListEvictionStrategy<>((int) (maxSize * 0.67), (int) (maxSize * 0.33));
        };
    }

    /**
     * 创建双链表淘汰策略
     *
     * @param maxActiveSize Active List最大容量
     * @param maxInactiveSize Inactive List最大容量
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 双链表淘汰策略实例
     */
    public static <K, V> TwoListEvictionStrategy<K, V> createTwoList(
            int maxActiveSize, int maxInactiveSize) {
        return new TwoListEvictionStrategy<>(maxActiveSize, maxInactiveSize);
    }

    /**
     * 创建带淘汰判断器的双链表策略
     *
     * @param maxActiveSize Active List最大容量
     * @param maxInactiveSize Inactive List最大容量
     * @param evictionPredicate 淘汰判断器
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 双链表淘汰策略实例
     */
    public static <K, V> TwoListEvictionStrategy<K, V> createTwoListWithPredicate(
            int maxActiveSize, int maxInactiveSize, Predicate<V> evictionPredicate) {
        return new TwoListEvictionStrategy<>(maxActiveSize, maxInactiveSize, evictionPredicate);
    }

    /**
     * 创建默认的淘汰策略(双链表)
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 双链表淘汰策略实例
     */
    public static <K, V> EvictionStrategy<K, V> createDefault() {
        return new TwoListEvictionStrategy<>();
    }

    /** 淘汰策略类型 */
    public enum StrategyType {
        /** 双链表策略 */
        TWO_LIST
    }
}
