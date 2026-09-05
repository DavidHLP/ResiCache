package io.github.davidhlp.spring.cache.redis.cache;



/**
 * 用于计算键的布隆过滤器位位置的策略。
 */
interface BloomHashStrategy {

    int[] positionsFor(String key, BloomFilterConfig config);
}
