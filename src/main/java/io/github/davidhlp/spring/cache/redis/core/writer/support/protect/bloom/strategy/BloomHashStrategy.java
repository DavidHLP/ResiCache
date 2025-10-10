package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.strategy;

import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.BloomFilterConfig;

/**
 * 用于计算键的布隆过滤器位位置的策略。
 */
public interface BloomHashStrategy {

    int[] positionsFor(String key, BloomFilterConfig config);
}
