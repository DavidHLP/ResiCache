package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * JVM + Redis 双层布隆过滤器，优先使用 JVM 过滤结果，必要时回退 Redis。
 * 
 * <p>设计说明：
 * <ul>
 *   <li>本地布隆过滤器用于快速检查，减少 Redis 访问</li>
 *   <li>Redis 布隆过滤器作为权威数据源，在集群间共享</li>
 *   <li>缓存驱逐时需要调用 clear() 同步清除两个过滤器</li>
 * </ul>
 * 
 * <p>注意事项：
 * <ul>
 *   <li>布隆过滤器本身存在误判率（false positive），这是预期行为</li>
 *   <li>本地过滤器命中时直接返回 true，不检查 Redis（性能优先）</li>
 *   <li>如果 Redis 过滤器被清除但本地未同步，可能出现短暂误判</li>
 * </ul>
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
        // 先检查本地过滤器（快速路径）
        if (localFilter.mightContain(cacheName, key)) {
            log.debug("Local bloom filter hit, skip Redis: cacheName={}, key={}", cacheName, key);
            return true;
        }

        // 本地未命中，检查 Redis（权威数据源）
        boolean remoteHit = remoteFilter.mightContain(cacheName, key);
        if (remoteHit) {
            // Redis 命中，预热本地过滤器
            log.debug("Redis bloom filter hit after local miss, warm local: cacheName={}, key={}", cacheName, key);
            localFilter.add(cacheName, key);
        } else {
            log.debug("Redis bloom filter miss confirmed: cacheName={}, key={}", cacheName, key);
        }
        return remoteHit;
    }

    @Override
    public void clear(String cacheName) {
        // 先清除本地，再清除远程，确保一致性
        localFilter.clear(cacheName);
        remoteFilter.clear(cacheName);
        log.debug("Cleared both local and remote bloom filters: cacheName={}", cacheName);
    }
}
