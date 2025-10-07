package com.david.spring.cache.redis.core.writer.support.protect.bloom.filter;

/**
 * 用于保护缺失键的缓存布隆过滤器抽象。
 */
public interface BloomIFilter {

    /**
     * 将键添加到指定缓存的布隆过滤器中。
     */
    void add(String cacheName, String key);

    /**
     * 检查布隆过滤器是否可能包含该键。
     */
    boolean mightContain(String cacheName, String key);

    /** 清空指定缓存的布隆过滤器。 */
    void clear(String cacheName);
}