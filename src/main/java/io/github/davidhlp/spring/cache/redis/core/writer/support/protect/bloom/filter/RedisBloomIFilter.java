package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.filter;

import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.BloomFilterConfig;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.strategy.BloomHashStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 基于Redis的布隆过滤器实现。
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
            for (int position : positions) {
                hashOperations.put(bloomKey, Integer.toString(position), "1");
            }
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
            for (int position : hashStrategy.positionsFor(key, config)) {
                Object value = hashOperations.get(bloomKey, Integer.toString(position));
                if (value == null) {
                    log.debug(
                            "Bloom filter miss (definitely does not exist): cacheName={}, key={}",
                            cacheName,
                            key);
                    return false;
                }
            }

            log.debug("Bloom filter hit (might exist): cacheName={}, key={}", cacheName, key);
            return true;
        } catch (Exception e) {
            log.error("Bloom filter check failed: cacheName={}, key={}", cacheName, key, e);
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
