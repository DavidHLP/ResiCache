package io.github.davidhlp.spring.cache.redis.spi;

/**
 * 布隆过滤器 SPI 接口
 *
 * <p>提供可插拔的布隆过滤器实现，允许用户替换默认的 RedisBloomFilter 实现。
 *
 * <p>使用方式：
 * <ol>
 *   <li>实现此接口创建自定义布隆过滤器</li>
 *   <li>在 `META-INF/services/io.github.davidhlp.spring.cache.redis.spi.BloomFilterProvider` 文件中注册</li>
 *   <li>设置 `resi-cache.bloom-filter.provider=custom` 启用自定义实现</li>
 * </ol>
 */
public interface BloomFilterProvider {

    /**
     * 获取布隆过滤器实例
     *
     * @param cacheName 缓存名称
     * @param expectedInsertions 预期插入数量
     * @param falseProbability 期望误判率
     * @return 布隆过滤器实例
     */
    BloomFilter create(String cacheName, long expectedInsertions, double falseProbability);

    /**
     * 获取 Provider 名称
     *
     * @return 实现名称，用于配置匹配
     */
    String getName();
}
