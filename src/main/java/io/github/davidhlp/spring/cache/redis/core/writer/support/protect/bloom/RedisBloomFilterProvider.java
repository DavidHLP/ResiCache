package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom;

import io.github.davidhlp.spring.cache.redis.spi.BloomFilter;
import io.github.davidhlp.spring.cache.redis.spi.BloomFilterProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于 Redis 的布隆过滤器 SPI 实现。
 *
 * <p>通过 {@link BloomSupport} 提供 Redis 集群兼容的布隆过滤器能力。
 */
@Slf4j
public class RedisBloomFilterProvider implements BloomFilterProvider {

    private final BloomSupport bloomSupport;

    public RedisBloomFilterProvider(BloomSupport bloomSupport) {
        this.bloomSupport = bloomSupport;
    }

    @Override
    public BloomFilter create(String cacheName, long expectedInsertions, double falseProbability) {
        return new BloomFilter() {
            @Override
            public boolean mightContain(String key) {
                return bloomSupport.mightContain(cacheName, key);
            }

            @Override
            public void add(String key) {
                bloomSupport.add(cacheName, key);
            }

            @Override
            public void clear() {
                bloomSupport.clear(cacheName);
            }
        };
    }

    @Override
    public String getName() {
        return "redis";
    }
}
