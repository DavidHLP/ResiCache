package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.BloomFilterConfig;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.strategy.BloomHashStrategy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于Redis的布隆过滤器实现。
 *
 * <p>性能优化：使用 Redis Pipeline 批量操作，减少网络往返次数。
 * <p>异常处理：所有异常都会被记录并通过 Micrometer 指标监控。
 */
@Slf4j
@Component("redisBloomFilter")
public class RedisBloomIFilter implements BloomIFilter {

    private final RedisTemplate<String, String> redisTemplate;
    private final BloomFilterConfig config;
    private final BloomHashStrategy hashStrategy;
    private final MeterRegistry meterRegistry;

    private Cache<String, int[]> hashPositionCache;
    private Counter checkFailureCounter;
    private Counter addFailureCounter;

    @Autowired
    public RedisBloomIFilter(
            RedisTemplate<String, String> redisTemplate,
            BloomFilterConfig config,
            BloomHashStrategy hashStrategy,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.config = config;
        this.hashStrategy = hashStrategy;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        this.hashPositionCache = Caffeine.newBuilder()
                .maximumSize(config.getHashCacheSize())
                .build();
        if (meterRegistry != null) {
            this.checkFailureCounter = Counter.builder("bloomsift.check.failures")
                    .description("Number of bloom filter check failures")
                    .register(meterRegistry);
            this.addFailureCounter = Counter.builder("bloomsift.add.failures")
                    .description("Number of bloom filter add failures")
                    .register(meterRegistry);
        }
    }

    @Override
    public void add(String cacheName, String key) {
        if (cacheName == null || key == null) {
            return;
        }

        String bloomKey = bloomKey(cacheName);
        String cacheEntryKey = cacheName + "::" + key;
        try {
            int[] positions = hashPositionCache.get(cacheEntryKey,
                    k -> hashStrategy.positionsFor(key, config));

            // 使用 Redis Pipeline 批量写入，减少网络往返
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (int position : positions) {
                    connection.hashCommands().hSet(
                            bloomKey.getBytes(),
                            String.valueOf(position).getBytes(),
                            "1".getBytes());
                }
                return null;
            });

            log.debug(
                    "Bloom filter add: cacheName={}, key={}, positions={}",
                    cacheName,
                    key,
                    Arrays.toString(positions));
        } catch (Exception e) {
            log.error("Bloom filter add failed: cacheName={}, key={}", cacheName, key, e);
            if (addFailureCounter != null) {
                addFailureCounter.increment();
            }
        }
    }

    @Override
    public boolean mightContain(String cacheName, String key) {
        if (cacheName == null || key == null) {
            return false;
        }

        String bloomKey = bloomKey(cacheName);
        String cacheEntryKey = cacheName + "::" + key;
        try {
            int[] positions = hashPositionCache.get(cacheEntryKey,
                    k -> hashStrategy.positionsFor(key, config));

            // 使用 Pipeline 批量查询，减少网络往返
            List<byte[]> hashKeys = Arrays.stream(positions)
                    .mapToObj(p -> String.valueOf(p).getBytes())
                    .collect(Collectors.toList());

            List<Object> rawValues = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (byte[] hKey : hashKeys) {
                    connection.hashCommands().hGet(bloomKey.getBytes(), hKey);
                }
                return null;
            });

            // 检查是否有任何位置缺失
            for (int i = 0; i < rawValues.size(); i++) {
                if (rawValues.get(i) == null) {
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
            if (checkFailureCounter != null) {
                checkFailureCounter.increment();
            }
            // 异常时默认返回 true，避免误拒绝（安全侧）
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
            redisTemplate.delete(bloomKey);
            log.debug("Bloom filter deleted: cacheName={}", cacheName);
        } catch (Exception e) {
            log.error("Bloom filter delete failed: cacheName={}", cacheName, e);
        }
    }

    private String bloomKey(String cacheName) {
        return config.getKeyPrefix() + cacheName;
    }
}
