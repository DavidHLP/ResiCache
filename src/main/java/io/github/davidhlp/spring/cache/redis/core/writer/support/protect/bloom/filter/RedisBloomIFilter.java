package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.filter;

import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.BloomFilterConfig;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.strategy.BloomHashStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于Redis的布隆过滤器实现。
 * 
 * <p>性能优化：使用 Redis Pipeline 批量操作，减少网络往返次数。
 */
@Slf4j
@Component("redisBloomFilter")
@RequiredArgsConstructor
public class RedisBloomIFilter implements BloomIFilter {

    private final HashOperations<String, String, String> hashOperations;
    private final BloomFilterConfig config;
    private final BloomHashStrategy hashStrategy;

    @Override
    public void add(String cacheName, String key) {
        if (cacheName == null || key == null) {
            return;
        }

        String bloomKey = bloomKey(cacheName);
        try {
            int[] positions = hashStrategy.positionsFor(key, config);
            
            // 使用 Pipeline 批量写入，减少网络往返
            Map<String, String> data = new HashMap<>(positions.length);
            for (int position : positions) {
                data.put(Integer.toString(position), "1");
            }
            hashOperations.putAll(bloomKey, data);
            
            log.debug(
                    "Bloom filter add: cacheName={}, key={}, positions={}",
                    cacheName,
                    key,
                    Arrays.toString(positions));
        } catch (Exception e) {
            log.error("Bloom filter add failed: cacheName={}, key={}", cacheName, key, e);
        }
    }

    @Override
    public boolean mightContain(String cacheName, String key) {
        if (cacheName == null || key == null) {
            return false;
        }

        String bloomKey = bloomKey(cacheName);
        try {
            int[] positions = hashStrategy.positionsFor(key, config);
            
            // 使用 multiGet 批量查询，减少网络往返
            List<String> hashKeys = Arrays.stream(positions)
                    .mapToObj(Integer::toString)
                    .collect(Collectors.toList());
            List<String> values = hashOperations.multiGet(bloomKey, hashKeys);
            
            // 检查是否有任何位置缺失
            for (int i = 0; i < values.size(); i++) {
                if (values.get(i) == null) {
                    log.debug(
                            "Bloom filter miss (definitely does not exist): cacheName={}, key={}, missingPosition={}",
                            cacheName,
                            key,
                            positions[i]);
                    return false;
                }
            }

            log.debug("Bloom filter hit (might exist): cacheName={}, key={}", cacheName, key);
            return true;
        } catch (Exception e) {
            log.error("Bloom filter check failed: cacheName={}, key={}", cacheName, key, e);
            // 异常时默认返回 true，避免误拒绝
            return true;
        }
    }

    @Override
    public void clear(String cacheName) {
        if (cacheName == null) {
            return;
        }

        String bloomKey = bloomKey(cacheName);
        try {
            hashOperations.getOperations().delete(bloomKey);
            log.debug("Bloom filter deleted: cacheName={}", cacheName);
        } catch (Exception e) {
            log.error("Bloom filter delete failed: cacheName={}", cacheName, e);
        }
    }

    private String bloomKey(String cacheName) {
        return config.getKeyPrefix() + cacheName;
    }
}
