package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * JVM + Redis 双层布隆过滤器，优先使用 JVM 过滤结果，必要时回退 Redis。
 */
@Slf4j
@Primary
@Component("hierarchicalBloomFilter")
public class HierarchicalBloomIFilter implements BloomIFilter {

    private final BloomIFilter localFilter;
    private final BloomIFilter remoteFilter;

    public HierarchicalBloomIFilter(@Qualifier("localBloomFilter") BloomIFilter localFilter, @Qualifier("redisBloomFilter") BloomIFilter remoteFilter) {
        this.localFilter = localFilter;
        this.remoteFilter = remoteFilter;
    }

    @Override
    public void add(String cacheName, String key) {
        localFilter.add(cacheName, key);
        remoteFilter.add(cacheName, key);
    }

    @Override
    public boolean mightContain(String cacheName, String key) {
        if (localFilter.mightContain(cacheName, key)) {
            log.debug("Local bloom filter hit, skip Redis: cacheName={}, key={}", cacheName, key);
            return true;
        }

        boolean remoteHit = remoteFilter.mightContain(cacheName, key);
        if (remoteHit) {
            log.debug("Redis bloom filter hit after local miss, warm local: cacheName={}, key={}", cacheName, key);
            localFilter.add(cacheName, key);
        } else {
            log.debug("Redis bloom filter miss confirmed: cacheName={}, key={}", cacheName, key);
        }
        return remoteHit;
    }

    @Override
    public void clear(String cacheName) {
        localFilter.clear(cacheName);
        remoteFilter.clear(cacheName);
    }
}
