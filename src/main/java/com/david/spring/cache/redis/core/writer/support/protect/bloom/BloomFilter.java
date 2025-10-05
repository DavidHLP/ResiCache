package com.david.spring.cache.redis.core.writer.support.protect.bloom;

/**
 * Abstraction for a cache bloom filter guarding missing keys.
 */
public interface BloomFilter {

    /** Adds a key to the bloom filter for the given cache. */
    void add(String cacheName, String key);

    /** Checks whether the bloom filter might contain the key. */
    boolean mightContain(String cacheName, String key);

    /** Clears the bloom filter for the cache. */
    void clear(String cacheName);
}