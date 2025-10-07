package com.david.spring.cache.redis.core.writer.support.protect.bloom.filter;

import com.david.spring.cache.redis.core.writer.support.protect.bloom.BloomFilterConfig;
import com.david.spring.cache.redis.core.writer.support.protect.bloom.strategy.BloomHashStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JVM 内存级别的布隆过滤器，用于降低 Redis 查询的频率。
 */
@Slf4j
@Component("localBloomFilter")
@RequiredArgsConstructor
public class LocalBloomIFilter implements BloomIFilter {

    private final BloomFilterConfig config;
    private final BloomHashStrategy hashStrategy;
    private final ConcurrentMap<String, BitSet> localFilters = new ConcurrentHashMap<>();

    @Override
    public void add(String cacheName, String key) {
        if (cacheName == null || key == null) {
            return;
        }
        BitSet bitSet = bitSetFor(cacheName);
        int[] positions = hashStrategy.positionsFor(key, config);
        synchronized (bitSet) {
            for (int position : positions) {
                bitSet.set(position);
            }
        }
        log.debug(
                "Local bloom add: cacheName={}, key={}, positions={}",
                cacheName,
                key,
                Arrays.toString(positions));
    }

    @Override
    public boolean mightContain(String cacheName, String key) {
        if (cacheName == null || key == null) {
            return false;
        }
        BitSet bitSet = localFilters.get(cacheName);
        if (bitSet == null) {
            return false;
        }
        int[] positions = hashStrategy.positionsFor(key, config);
        synchronized (bitSet) {
            for (int position : positions) {
                if (!bitSet.get(position)) {
                    log.debug(
                            "Local bloom miss: cacheName={}, key={}, position={}",
                            cacheName,
                            key,
                            position);
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void clear(String cacheName) {
        if (cacheName != null) {
            localFilters.remove(cacheName);
        }
    }

    private BitSet bitSetFor(String cacheName) {
        return localFilters.computeIfAbsent(cacheName, name -> new BitSet(config.getBitSize()));
    }
}
