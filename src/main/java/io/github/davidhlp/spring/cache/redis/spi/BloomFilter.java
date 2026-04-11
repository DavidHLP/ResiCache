package io.github.davidhlp.spring.cache.redis.spi;

/**
 * 布隆过滤器接口
 *
 * <p>定义布隆过滤器的基本操作，用于防止缓存穿透。
 */
public interface BloomFilter {

    /**
     * 判断指定 key 是否可能存在于缓存中
     *
     * @param key 缓存键
     * @return true 表示可能存在（可能误判），false 表示一定不存在
     */
    boolean mightContain(String key);

    /**
     * 将 key 加入布隆过滤器
     *
     * @param key 缓存键
     */
    void add(String key);

    /**
     * 清空布隆过滤器
     */
    void clear();
}
