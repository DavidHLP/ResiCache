package com.david.spring.cache.redis.core.writer.support.protect.bloom;

/** Strategy for calculating bloom filter bit positions for a key. */
public interface BloomHashStrategy {

    int[] positionsFor(String key, BloomFilterConfig config);
}
