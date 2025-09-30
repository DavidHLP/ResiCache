package com.david.spring.cache.redis.core.writer;

import com.david.spring.cache.redis.register.RedisCacheRegister;
import com.david.spring.cache.redis.register.operation.RedisCacheableOperation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.assertj.core.util.VisibleForTesting;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RequiredArgsConstructor
public class RedisProCacheWriter implements RedisCacheWriter {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CacheStatisticsCollector statistics;
    private final RedisCacheRegister redisCacheRegister;
    private final WriterChainableUtils writerChainableUtils;

    /**
     * 统一的TTL处理逻辑 优先级：上下文配置的TTL > 方法参数传入的TTL
     *
     * @param name 缓存名称
     * @param key 缓存key
     * @param ttl 方法参数传入的TTL
     * @return TTL计算结果
     */
    private TtlCalculationResult calculateTtl(String name, String key, @Nullable Duration ttl) {
        if (ttl == null) ttl = DEFAULT_TTL;
        // 先尝试从注册器获取缓存操作上下文
        RedisCacheableOperation cacheOperation =
                redisCacheRegister.getCacheableOperation(name, key);

        // 如果上下文存在且配置了TTL，优先使用上下文配置
        if (cacheOperation != null && cacheOperation.getTtl() > 0) {
            long finalTtl =
                    writerChainableUtils
                            .TtlSupport()
                            .calculateFinalTtl(
                                    cacheOperation.getTtl(),
                                    cacheOperation.isRandomTtl(),
                                    cacheOperation.getVariance());

            log.debug(
                    "Using context TTL configuration: cacheName={}, key={}, baseTtl={}s, finalTtl={}s, randomTtl={}, variance={}",
                    name,
                    key,
                    cacheOperation.getTtl(),
                    finalTtl,
                    cacheOperation.isRandomTtl(),
                    cacheOperation.getVariance());

            return new TtlCalculationResult(finalTtl, true, true);
        }

        // 否则使用方法参数传入的TTL
        if (writerChainableUtils.TtlSupport().shouldApplyTtl(ttl)) {
            long finalTtl = ttl.getSeconds();
            log.debug("Using parameter TTL: cacheName={}, key={}, ttl={}s", name, key, finalTtl);
            return new TtlCalculationResult(finalTtl, true, false);
        }

        // 不应用TTL
        return new TtlCalculationResult(-1, false, false);
    }

    @Override
    @Nullable
    public byte[] get(@NonNull String name, @NonNull byte[] key) {
        return get(name, key, null);
    }

    @Override
    @Nullable
    public byte[] get(@NonNull String name, @NonNull byte[] key, @Nullable Duration ttl) {
        String redisKey = new String(key);
        log.debug("Starting cache retrieval: cacheName={}, key={}, ttl={}", name, redisKey, ttl);
        try {
            CachedValue cachedValue = (CachedValue) redisTemplate.opsForValue().get(redisKey);

            statistics.incGets(name);

            if (cachedValue == null) {
                log.debug("Cache miss - data not found: cacheName={}, key={}", name, redisKey);
                statistics.incMisses(name);
                return null;
            }

            if (cachedValue.isExpired()) {
                log.debug("Cache miss - data expired: cacheName={}, key={}", name, redisKey);
                statistics.incMisses(name);
                return null;
            }

            // 检查是否需要预刷新
            RedisCacheableOperation cacheOperation =
                    redisCacheRegister.getCacheableOperation(name, redisKey);
            if (cacheOperation != null && cacheOperation.isEnablePreRefresh()) {
                boolean needsPreRefresh =
                        writerChainableUtils
                                .TtlSupport()
                                .shouldPreRefresh(
                                        cachedValue.getCreatedTime(),
                                        cachedValue.getTtl(),
                                        cacheOperation.getPreRefreshThreshold());

                if (needsPreRefresh) {
                    log.info(
                            "Cache needs pre-refresh: cacheName={}, key={}, threshold={}, remainingTtl={}s",
                            name,
                            redisKey,
                            cacheOperation.getPreRefreshThreshold(),
                            cachedValue.getRemainingTtl());
                    // 标记为需要刷新（实际的刷新逻辑由上层AOP处理）
                    // 这里返回null，触发缓存miss，让AOP重新加载数据
                    statistics.incMisses(name);
                    return null;
                }
            }

            log.debug(
                    "Cache hit: cacheName={}, key={}, remainingTtl={}s",
                    name,
                    redisKey,
                    cachedValue.getRemainingTtl());
            statistics.incHits(name);
            cachedValue.updateAccess();
            redisTemplate
                    .opsForValue()
                    .set(redisKey, cachedValue, Duration.ofSeconds(cachedValue.getRemainingTtl()));

            byte[] result = objectMapper.writeValueAsBytes(cachedValue.getValue());
            log.debug(
                    "Successfully serialized cache data: cacheName={}, key={}, dataSize={} bytes",
                    name,
                    redisKey,
                    result.length);
            return result;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize cached value", e);
            statistics.incMisses(name);
            return null;
        } catch (Exception e) {
            log.error("Failed to get value from cache: {}", name, e);
            statistics.incMisses(name);
            return null;
        }
    }

    @Override
    public boolean supportsAsyncRetrieve() {
        return RedisCacheWriter.super.supportsAsyncRetrieve();
    }

    @Override
    @NonNull
    public CompletableFuture<byte[]> retrieve(@NonNull String name, @NonNull byte[] key) {
        return retrieve(name, key, null);
    }

    @Override
    @NonNull
    public CompletableFuture<byte[]> retrieve(
            @NonNull String name, @NonNull byte[] key, @Nullable Duration ttl) {
        return CompletableFuture.supplyAsync(() -> get(name, key, ttl));
    }

    @Override
    public void put(
            @NonNull String name,
            @NonNull byte[] key,
            @NonNull byte[] value,
            @Nullable Duration ttl) {
        String redisKey = new String(key);
        log.debug(
                "Starting cache storage: cacheName={}, key={}, ttl={}, dataSize={} bytes",
                name,
                redisKey,
                ttl,
                value.length);
        try {
            Object deserializedValue = objectMapper.readValue(value, Object.class);

            // 使用统一的TTL计算逻辑
            TtlCalculationResult ttlResult = calculateTtl(name, redisKey, ttl);

            CachedValue cachedValue;
            if (ttlResult.shouldApply) {
                cachedValue = CachedValue.of(deserializedValue, ttlResult.finalTtl);
                redisTemplate
                        .opsForValue()
                        .set(redisKey, cachedValue, Duration.ofSeconds(ttlResult.finalTtl));
                log.debug(
                        "Successfully stored cache data with TTL: cacheName={}, key={}, ttl={}s, fromContext={}",
                        name,
                        redisKey,
                        ttlResult.finalTtl,
                        ttlResult.fromContext);
            } else {
                cachedValue = CachedValue.of(deserializedValue, -1);
                redisTemplate.opsForValue().set(redisKey, cachedValue);
                log.debug(
                        "Successfully stored permanent cache data: cacheName={}, key={}",
                        name,
                        redisKey);
            }

            statistics.incPuts(name);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize value for caching", e);
        } catch (Exception e) {
            log.error("Failed to put value to cache: {}", name, e);
        }
    }

    @Override
    @NonNull
    public CompletableFuture<Void> store(
            @NonNull String name,
            @NonNull byte[] key,
            @NonNull byte[] value,
            @Nullable Duration ttl) {
        return CompletableFuture.runAsync(() -> put(name, key, value, ttl));
    }

    @Override
    @Nullable
    public byte[] putIfAbsent(
            @NonNull String name,
            @NonNull byte[] key,
            @NonNull byte[] value,
            @Nullable Duration ttl) {
        String redisKey = new String(key);
        log.debug(
                "Starting conditional cache storage: cacheName={}, key={}, ttl={}, dataSize={} bytes",
                name,
                redisKey,
                ttl,
                value.length);
        try {
            CachedValue existingValue = (CachedValue) redisTemplate.opsForValue().get(redisKey);

            if (existingValue != null && !existingValue.isExpired()) {
                log.debug(
                        "Cache data exists and not expired, returning existing value: cacheName={}, key={}",
                        name,
                        redisKey);
                return objectMapper.writeValueAsBytes(existingValue.getValue());
            }

            Object deserializedValue = objectMapper.readValue(value, Object.class);

            // 使用统一的TTL计算逻辑
            TtlCalculationResult ttlResult = calculateTtl(name, redisKey, ttl);

            CachedValue cachedValue;
            Boolean success;

            if (ttlResult.shouldApply) {
                cachedValue = CachedValue.of(deserializedValue, ttlResult.finalTtl);
                success =
                        redisTemplate
                                .opsForValue()
                                .setIfAbsent(
                                        redisKey,
                                        cachedValue,
                                        Duration.ofSeconds(ttlResult.finalTtl));
                log.debug(
                        "Attempting conditional storage with TTL: cacheName={}, key={}, ttl={}s, fromContext={}",
                        name,
                        redisKey,
                        ttlResult.finalTtl,
                        ttlResult.fromContext);
            } else {
                cachedValue = CachedValue.of(deserializedValue, -1);
                success = redisTemplate.opsForValue().setIfAbsent(redisKey, cachedValue);
                log.debug(
                        "Attempting conditional storage without TTL: cacheName={}, key={}",
                        name,
                        redisKey);
            }

            if (Boolean.TRUE.equals(success)) {
                log.debug("Conditional storage succeeded: cacheName={}, key={}", name, redisKey);
                statistics.incPuts(name);
                return null;
            } else {
                log.debug(
                        "Conditional storage failed, retrieving existing value: cacheName={}, key={}",
                        name,
                        redisKey);
                CachedValue actualValue = (CachedValue) redisTemplate.opsForValue().get(redisKey);
                return actualValue != null
                        ? objectMapper.writeValueAsBytes(actualValue.getValue())
                        : null;
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to process value for putIfAbsent", e);
            return null;
        } catch (Exception e) {
            log.error("Failed to putIfAbsent value to cache: {}", name, e);
            return null;
        }
    }

    @Override
    public void remove(@NonNull String name, @NonNull byte[] key) {
        String redisKey = new String(key);
        log.debug("Starting cache data removal: cacheName={}, key={}", name, redisKey);
        try {
            Boolean deleted = redisTemplate.delete(redisKey);
            statistics.incDeletes(name);
            log.debug(
                    "Cache data removal completed: cacheName={}, key={}, deleted={}",
                    name,
                    redisKey,
                    deleted);
        } catch (Exception e) {
            log.error("Failed to remove value from cache: {}", name, e);
        }
    }

    @Override
    public void clean(@NonNull String name, @NonNull byte[] pattern) {
        String keyPattern = new String(pattern);
        log.debug("Starting batch cache cleanup: cacheName={}, pattern={}", name, keyPattern);
        try {
            var keys = redisTemplate.keys(keyPattern);
            log.debug(
                    "Found matching cache keys: cacheName={}, pattern={}, count={}",
                    name,
                    keyPattern,
                    keys.size());
            if (!keys.isEmpty()) {
                Long deleteCount = redisTemplate.delete(keys);
                statistics.incDeletesBy(name, deleteCount.intValue());
                log.debug(
                        "Batch cache cleanup completed: cacheName={}, pattern={}, deletedCount={}",
                        name,
                        keyPattern,
                        deleteCount);
            } else {
                log.debug(
                        "No matching cache keys found: cacheName={}, pattern={}", name, keyPattern);
            }
        } catch (Exception e) {
            log.error("Failed to clean cache: {}", name, e);
        }
    }

    @Override
    public void clearStatistics(@NonNull String name) {
        log.debug("Starting cache statistics cleanup: cacheName={}", name);
        statistics.reset(name);
        log.debug("Cache statistics cleanup completed: cacheName={}", name);
    }

    @Override
    @NonNull
    public RedisCacheWriter withStatisticsCollector(
            @NonNull CacheStatisticsCollector cacheStatisticsCollector) {
        return new RedisProCacheWriter(
                redisTemplate, cacheStatisticsCollector, redisCacheRegister, writerChainableUtils);
    }

    @Override
    @NonNull
    public CacheStatistics getCacheStatistics(@NonNull String cacheName) {
        return statistics.getCacheStatistics(cacheName);
    }

    protected long getTtl(String redisKey) {
        CachedValue cachedValue = (CachedValue) redisTemplate.opsForValue().get(redisKey);
        if (cachedValue != null) {
            return cachedValue.getTtl();
        }
        return -1;
    }

    protected long getExpiration(String redisKey) {
        return redisTemplate.getExpire(redisKey);
    }

    /** TTL计算结果 */
    private record TtlCalculationResult(long finalTtl, boolean shouldApply, boolean fromContext) {}
}
