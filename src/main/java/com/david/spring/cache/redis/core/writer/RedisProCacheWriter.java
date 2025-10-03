package com.david.spring.cache.redis.core.writer;

import com.david.spring.cache.redis.core.writer.support.PreRefreshMode;
import com.david.spring.cache.redis.register.RedisCacheRegister;
import com.david.spring.cache.redis.register.operation.RedisCacheableOperation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RequiredArgsConstructor
public class RedisProCacheWriter implements RedisCacheWriter {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);
    private final RedisTemplate<String, Object> redisTemplate;
    private final ValueOperations<String, Object> valueOperations;
    private final CacheStatisticsCollector statistics;
    private final RedisCacheRegister redisCacheRegister;
    private final WriterChainableUtils writerChainableUtils;

    /**
     * 统一的TTL处理逻辑 优先级：上下文配置的TTL > 方法参数传入的TTL
     *
     * @param name 缓存名称
     * @param key 缓存key (完整的Redis key，格式为 {cacheName}::{actualKey})
     * @param ttl 方法参数传入的TTL
     * @return TTL计算结果
     */
    private TtlCalculationResult calculateTtl(String name, String key, @Nullable Duration ttl) {
        if (ttl == null) ttl = DEFAULT_TTL;

        // 从完整的Redis key中提取实际的key部分
        // Redis key格式: {cacheName}::{actualKey}
        String actualKey = extractActualKey(name, key);

        // 先尝试从注册器获取缓存操作上下文
        RedisCacheableOperation cacheOperation =
                redisCacheRegister.getCacheableOperation(name, actualKey);

        // 如果上下文存在且配置了TTL，优先使用上下文配置
        if (cacheOperation != null && cacheOperation.getTtl() > 0) {
            long finalTtl =
                    writerChainableUtils
                            .TtlSupport()
                            .calculateFinalTtl(
                                    cacheOperation.getTtl(),
                                    cacheOperation.isRandomTtl(),
                                    cacheOperation.getVariance());

            logContextTtlConfiguration(name, key, cacheOperation, finalTtl);

            return new TtlCalculationResult(finalTtl, true, true);
        }

        // 否则使用方法参数传入的TTL
        if (writerChainableUtils.TtlSupport().shouldApplyTtl(ttl)) {
            long finalTtl = ttl.getSeconds();
            logParameterTtl(name, key, finalTtl);
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
        String redisKey = writerChainableUtils.TypeSupport().bytesToString(key);
        String actualKey = extractActualKey(name, redisKey);

        // 检查是否需要使用sync模式
        RedisCacheableOperation cacheOperation =
                redisCacheRegister.getCacheableOperation(name, actualKey);

        if (cacheOperation != null && cacheOperation.isSync()) {
            logSyncMode(name, redisKey);
            return getWithSync(name, redisKey, actualKey, ttl, cacheOperation);
        }

        // 普通模式
        return getNormal(name, redisKey, actualKey, ttl, cacheOperation);
    }

    /** 同步模式获取缓存（防止缓存击穿） */
    @Nullable
    private byte[] getWithSync(
            String name,
            String redisKey,
            String actualKey,
            @Nullable Duration ttl,
            @Nullable RedisCacheableOperation cacheOperation) {
        return writerChainableUtils
                .SyncSupport()
                .executeSync(
                        redisKey,
                        () -> getNormal(name, redisKey, actualKey, ttl, cacheOperation),
                        10); // 默认10秒超时
    }

    /** 普通模式获取缓存 */
    @Nullable
    private byte[] getNormal(
            String name,
            String redisKey,
            String actualKey,
            @Nullable Duration ttl,
            @Nullable RedisCacheableOperation cacheOperation) {
        logStartingCacheRetrieval(name, redisKey, ttl);

        // 如果启用了布隆过滤器，先检查
        if (cacheOperation != null && cacheOperation.isUseBloomFilter()) {
            if (!writerChainableUtils.BloomFilterSupport().mightContain(name, actualKey)) {
                logBloomFilterRejects(name, redisKey);
                statistics.incMisses(name);
                return null;
            }
        }

        try {
            CachedValue cachedValue = (CachedValue) valueOperations.get(redisKey);

            statistics.incGets(name);

            if (cachedValue == null) {
                logCacheMissNotFound(name, redisKey);
                statistics.incMisses(name);
                return null;
            }

            if (cachedValue.isExpired()) {
                logCacheMissExpired(name, redisKey);
                statistics.incMisses(name);
                return null;
            }

            // 检查是否需要预刷新
            if (cacheOperation != null && cacheOperation.isEnablePreRefresh()) {
                boolean needsPreRefresh =
                        writerChainableUtils
                                .TtlSupport()
                                .shouldPreRefresh(
                                        cachedValue.getCreatedTime(),
                                        cachedValue.getTtl(),
                                        cacheOperation.getPreRefreshThreshold());

                if (needsPreRefresh) {
                    logCacheNeedsPreRefresh(name, redisKey, cacheOperation, cachedValue);

                    // 根据预刷新模式决定处理方式
                    PreRefreshMode mode = cacheOperation.getPreRefreshMode();
                    if (mode == null) {
                        mode = PreRefreshMode.SYNC; // 默认同步模式
                    }

                    if (mode == PreRefreshMode.SYNC) {
                        // 同步模式：返回 null，触发缓存未命中
                        logSyncPreRefreshTriggered(name, redisKey);
                        statistics.incMisses(name);
                        return null;
                    } else {
                        // 异步模式：返回旧值，异步删除缓存
                        logAsyncPreRefreshTriggered(name, redisKey);
                        writerChainableUtils
                                .PreRefreshSupport()
                                .submitAsyncRefresh(
                                        redisKey,
                                        () -> {
                                            try {
                                                redisTemplate.delete(redisKey);
                                                logAsyncPreRefreshCacheDeleted(name, redisKey);
                                            } catch (Exception e) {
                                                logAsyncPreRefreshFailed(name, redisKey, e);
                                            }
                                        });
                        // 继续返回旧值，不增加 miss 统计
                    }
                }
            }

            logCacheHit(name, redisKey, cachedValue);
            statistics.incHits(name);
            cachedValue.updateAccess();
            valueOperations.set(
                    redisKey, cachedValue, Duration.ofSeconds(cachedValue.getRemainingTtl()));

            // 使用NullValueSupport处理返回值
            Object value = cachedValue.getValue();
            byte[] result =
                    writerChainableUtils.NullValueSupport().toReturnValue(value, name, redisKey);

            if (result != null && !writerChainableUtils.NullValueSupport().isNullValue(value)) {
                logSuccessfullySerializedCacheData(name, redisKey, result);
            }
            return result;
        } catch (Exception e) {
            logFailedToGetValueFromCache(name, e);
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

    /**
     * 带操作配置的 put 方法（从 RedisProCache 调用）
     *
     * @param name 缓存名称
     * @param key 缓存key
     * @param value 缓存值
     * @param ttl TTL
     * @param operation 缓存操作配置
     */
    public void put(
            @NonNull String name,
            @NonNull byte[] key,
            @NonNull byte[] value,
            @Nullable Duration ttl,
            @NonNull RedisCacheableOperation operation) {
        String redisKey = writerChainableUtils.TypeSupport().bytesToString(key);
        logStartingCacheStorageWithOperation(name, redisKey, ttl, value, operation);
        try {
            Object deserializedValue =
                    writerChainableUtils.TypeSupport().deserializeFromBytes(value);

            // 处理null值：如果值为null且不允许缓存null，则直接返回
            if (deserializedValue == null
                    && !writerChainableUtils.NullValueSupport().shouldCacheNull(operation)) {
                logSkippingNullValueCaching(name, redisKey);
                return;
            }

            // 将null值转换为特殊标记（如果需要）
            Object storeValue =
                    writerChainableUtils
                            .NullValueSupport()
                            .toStoreValue(deserializedValue, operation);

            // 使用操作中的TTL配置
            long finalTtl =
                    writerChainableUtils
                            .TtlSupport()
                            .calculateFinalTtl(
                                    operation.getTtl(),
                                    operation.isRandomTtl(),
                                    operation.getVariance());

            CachedValue cachedValue = CachedValue.of(storeValue, finalTtl);
            valueOperations.set(redisKey, cachedValue, Duration.ofSeconds(finalTtl));

            // 如果启用了布隆过滤器，添加到布隆过滤器
            if (operation.isUseBloomFilter()) {
                String actualKey = extractActualKey(name, redisKey);
                writerChainableUtils.BloomFilterSupport().add(name, actualKey);
            }

            logSuccessfullyStoredCacheDataWithOperationTtl(
                    name, redisKey, operation, finalTtl, deserializedValue);

            statistics.incPuts(name);
        } catch (Exception e) {
            logFailedToPutValueToCache(name, e);
        }
    }

    @Override
    public void put(
            @NonNull String name,
            @NonNull byte[] key,
            @NonNull byte[] value,
            @Nullable Duration ttl) {
        String redisKey = writerChainableUtils.TypeSupport().bytesToString(key);
        logStartingCacheStorage(name, redisKey, ttl, value);
        try {
            Object deserializedValue =
                    writerChainableUtils.TypeSupport().deserializeFromBytes(value);

            // 从完整的Redis key中提取实际的key部分
            String actualKey = extractActualKey(name, redisKey);

            // 获取缓存操作配置
            RedisCacheableOperation cacheOperation =
                    redisCacheRegister.getCacheableOperation(name, actualKey);

            // 处理null值：如果值为null且不允许缓存null，则直接返回
            if (deserializedValue == null
                    && !writerChainableUtils.NullValueSupport().shouldCacheNull(cacheOperation)) {
                logSkippingNullValueCaching(name, redisKey);
                return;
            }

            // 将null值转换为特殊标记（如果需要）
            Object storeValue =
                    writerChainableUtils
                            .NullValueSupport()
                            .toStoreValue(deserializedValue, cacheOperation);

            // 使用统一的TTL计算逻辑
            TtlCalculationResult ttlResult = calculateTtl(name, redisKey, ttl);

            CachedValue cachedValue;
            if (ttlResult.shouldApply) {
                cachedValue = CachedValue.of(storeValue, ttlResult.finalTtl);
                valueOperations.set(redisKey, cachedValue, Duration.ofSeconds(ttlResult.finalTtl));
                logSuccessfullyStoredCacheDataWithTtl(name, redisKey, ttlResult, deserializedValue);
            } else {
                cachedValue = CachedValue.of(storeValue, -1);
                valueOperations.set(redisKey, cachedValue);
                logSuccessfullyStoredPermanentCacheData(name, redisKey, deserializedValue);
            }

            // 如果启用了布隆过滤器，添加到布隆过滤器
            if (cacheOperation != null && cacheOperation.isUseBloomFilter()) {
                writerChainableUtils.BloomFilterSupport().add(name, actualKey);
            }

            statistics.incPuts(name);
        } catch (Exception e) {
            logFailedToPutValueToCache(name, e);
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
        String redisKey = writerChainableUtils.TypeSupport().bytesToString(key);
        String actualKey = extractActualKey(name, redisKey);

        // 检查是否需要使用sync模式
        RedisCacheableOperation cacheOperation =
                redisCacheRegister.getCacheableOperation(name, actualKey);

        if (cacheOperation != null && cacheOperation.isSync()) {
            logSyncModeForConditionalCacheStorage(name, redisKey);
            return putIfAbsentWithSync(name, redisKey, value, ttl);
        }

        // 普通模式
        return putIfAbsentNormal(name, redisKey, value, ttl);
    }

    /** 同步模式的 putIfAbsent（防止缓存击穿） */
    @Nullable
    private byte[] putIfAbsentWithSync(
            String name, String redisKey, byte[] value, @Nullable Duration ttl) {
        return writerChainableUtils
                .SyncSupport()
                .executeSync(redisKey, () -> putIfAbsentNormal(name, redisKey, value, ttl), 10);
    }

    /** 普通模式的 putIfAbsent */
    @Nullable
    private byte[] putIfAbsentNormal(
            String name, String redisKey, byte[] value, @Nullable Duration ttl) {
        logStartingConditionalCacheStorage(name, redisKey, ttl, value);
        try {
            // 从完整的Redis key中提取实际的key部分
            String actualKey = extractActualKey(name, redisKey);

            // 获取缓存操作配置
            RedisCacheableOperation cacheOperation =
                    redisCacheRegister.getCacheableOperation(name, actualKey);

            CachedValue existingValue = (CachedValue) valueOperations.get(redisKey);

            if (existingValue != null && !existingValue.isExpired()) {
                logCacheDataExistsAndNotExpired(name, redisKey);
                // 使用NullValueSupport处理返回值
                return writerChainableUtils
                        .NullValueSupport()
                        .toReturnValue(existingValue.getValue(), name, redisKey);
            }

            Object deserializedValue =
                    writerChainableUtils.TypeSupport().deserializeFromBytes(value);

            // 处理null值：如果值为null且不允许缓存null，则直接返回
            if (deserializedValue == null
                    && !writerChainableUtils.NullValueSupport().shouldCacheNull(cacheOperation)) {
                logSkippingNullValueCachingInPutIfAbsent(name, redisKey);
                return null;
            }

            // 将null值转换为特殊标记（如果需要）
            Object storeValue =
                    writerChainableUtils
                            .NullValueSupport()
                            .toStoreValue(deserializedValue, cacheOperation);

            // 使用统一的TTL计算逻辑
            TtlCalculationResult ttlResult = calculateTtl(name, redisKey, ttl);

            CachedValue cachedValue;
            Boolean success;

            if (ttlResult.shouldApply) {
                cachedValue = CachedValue.of(storeValue, ttlResult.finalTtl);
                success =
                        valueOperations.setIfAbsent(
                                redisKey, cachedValue, Duration.ofSeconds(ttlResult.finalTtl));
                logAttemptingConditionalStorageWithTtl(
                        name, redisKey, ttlResult, deserializedValue);
            } else {
                cachedValue = CachedValue.of(storeValue, -1);
                success = valueOperations.setIfAbsent(redisKey, cachedValue);
                logAttemptingConditionalStorageWithoutTtl(name, redisKey, deserializedValue);
            }

            if (Boolean.TRUE.equals(success)) {
                logConditionalStorageSucceeded(name, redisKey);

                // 如果启用了布隆过滤器，添加到布隆过滤器
                if (cacheOperation != null && cacheOperation.isUseBloomFilter()) {
                    writerChainableUtils.BloomFilterSupport().add(name, actualKey);
                }

                statistics.incPuts(name);
            } else {
                logConditionalStorageFailed(name, redisKey);
                CachedValue actualValue = (CachedValue) valueOperations.get(redisKey);
                if (actualValue != null) {
                    // 使用NullValueSupport处理返回值
                    return writerChainableUtils
                            .NullValueSupport()
                            .toReturnValue(actualValue.getValue(), name, redisKey);
                }
            }
            return null;
        } catch (Exception e) {
            logFailedToPutIfAbsentValueToCache(name, e);
            return null;
        }
    }

    @Override
    public void remove(@NonNull String name, @NonNull byte[] key) {
        String redisKey = writerChainableUtils.TypeSupport().bytesToString(key);
        logStartingCacheDataRemoval(name, redisKey);
        try {
            Boolean deleted = redisTemplate.delete(redisKey);
            statistics.incDeletes(name);
            logCacheDataRemovalCompleted(name, redisKey, deleted);
        } catch (Exception e) {
            logFailedToRemoveValueFromCache(name, e);
        }
    }

    @Override
    public void clean(@NonNull String name, @NonNull byte[] pattern) {
        String keyPattern = writerChainableUtils.TypeSupport().bytesToString(pattern);
        logStartingBatchCacheCleanup(name, keyPattern);
        try {
            var keys = redisTemplate.keys(keyPattern);
            logFoundMatchingCacheKeys(name, keyPattern, keys);
            if (!keys.isEmpty()) {
                Long deleteCount = redisTemplate.delete(keys);
                statistics.incDeletesBy(name, deleteCount.intValue());
                logBatchCacheCleanupCompleted(name, keyPattern, deleteCount);

                // 如果是清空整个缓存（匹配所有key），则同时清除布隆过滤器
                if (keyPattern.endsWith("*")) {
                    writerChainableUtils.BloomFilterSupport().delete(name);
                    logBloomFilterClearedWithCache(name);
                }
            } else {
                logNoMatchingCacheKeysFound(name, keyPattern);
            }
        } catch (Exception e) {
            logFailedToCleanCache(name, e);
        }
    }

    @Override
    public void clearStatistics(@NonNull String name) {
        logStartingCacheStatisticsCleanup(name);
        statistics.reset(name);
        logCacheStatisticsCleanupCompleted(name);
    }

    @Override
    @NonNull
    public RedisCacheWriter withStatisticsCollector(
            @NonNull CacheStatisticsCollector cacheStatisticsCollector) {
        return new RedisProCacheWriter(
                redisTemplate,
                valueOperations,
                cacheStatisticsCollector,
                redisCacheRegister,
                writerChainableUtils);
    }

    @Override
    @NonNull
    public CacheStatistics getCacheStatistics(@NonNull String cacheName) {
        return statistics.getCacheStatistics(cacheName);
    }

    protected long getTtl(String redisKey) {
        CachedValue cachedValue = (CachedValue) valueOperations.get(redisKey);
        if (cachedValue != null) {
            return cachedValue.getTtl();
        }
        return -1;
    }

    protected long getExpiration(String redisKey) {
        return redisTemplate.getExpire(redisKey);
    }

    /**
     * 从完整的Redis key中提取实际的key部分 Redis key格式: {cacheName}::{actualKey}
     *
     * @param cacheName 缓存名称
     * @param redisKey 完整的Redis key
     * @return 实际的key部分
     */
    private String extractActualKey(String cacheName, String redisKey) {
        String prefix = cacheName + "::";
        if (redisKey.startsWith(prefix)) {
            return redisKey.substring(prefix.length());
        }
        // 如果格式不匹配，返回原始key
        return redisKey;
    }

    // 日志封装方法
    private void logContextTtlConfiguration(
            String name, String key, RedisCacheableOperation cacheOperation, long finalTtl) {
        log.debug(
                "Using context TTL configuration: cacheName={}, key={}, baseTtl={}s, finalTtl={}s, randomTtl={}, variance={}",
                name,
                key,
                cacheOperation.getTtl(),
                finalTtl,
                cacheOperation.isRandomTtl(),
                cacheOperation.getVariance());
    }

    private void logParameterTtl(String name, String key, long finalTtl) {
        log.debug("Using parameter TTL: cacheName={}, key={}, ttl={}s", name, key, finalTtl);
    }

    private void logSyncMode(String name, String redisKey) {
        log.debug("Using sync mode for cache retrieval: cacheName={}, key={}", name, redisKey);
    }

    private void logStartingCacheRetrieval(String name, String redisKey, Duration ttl) {
        log.debug("Starting cache retrieval: cacheName={}, key={}, ttl={}", name, redisKey, ttl);
    }

    private void logCacheMissNotFound(String name, String redisKey) {
        log.debug("Cache miss - data not found: cacheName={}, key={}", name, redisKey);
    }

    private void logCacheMissExpired(String name, String redisKey) {
        log.debug("Cache miss - data expired: cacheName={}, key={}", name, redisKey);
    }

    private void logCacheNeedsPreRefresh(
            String name,
            String redisKey,
            RedisCacheableOperation cacheOperation,
            CachedValue cachedValue) {
        log.info(
                "Cache needs pre-refresh: cacheName={}, key={}, threshold={}, remainingTtl={}s",
                name,
                redisKey,
                cacheOperation.getPreRefreshThreshold(),
                cachedValue.getRemainingTtl());
    }

    private void logCacheHit(String name, String redisKey, CachedValue cachedValue) {
        log.debug(
                "Cache hit: cacheName={}, key={}, remainingTtl={}s",
                name,
                redisKey,
                cachedValue.getRemainingTtl());
    }

    private void logSuccessfullySerializedCacheData(String name, String redisKey, byte[] result) {
        log.debug(
                "Successfully serialized cache data: cacheName={}, key={}, dataSize={} bytes",
                name,
                redisKey,
                result.length);
    }

    private void logFailedToGetValueFromCache(String name, Exception e) {
        log.error("Failed to get value from cache: {}", name, e);
    }

    private void logStartingCacheStorageWithOperation(
            String name,
            String redisKey,
            Duration ttl,
            byte[] value,
            RedisCacheableOperation operation) {
        log.debug(
                "Starting cache storage with operation: cacheName={}, key={}, ttl={}, dataSize={} bytes, operationTtl={}",
                name,
                redisKey,
                ttl,
                value.length,
                operation.getTtl());
    }

    private void logSkippingNullValueCaching(String name, String redisKey) {
        log.debug(
                "Skipping null value caching (cacheNullValues=false): cacheName={}, key={}",
                name,
                redisKey);
    }

    private void logSuccessfullyStoredCacheDataWithOperationTtl(
            String name,
            String redisKey,
            RedisCacheableOperation operation,
            long finalTtl,
            Object deserializedValue) {
        log.debug(
                "Successfully stored cache data with operation TTL: cacheName={}, key={}, ttl={}s, randomTtl={}, variance={}, isNull={}",
                name,
                redisKey,
                finalTtl,
                operation.isRandomTtl(),
                operation.getVariance(),
                deserializedValue == null);
    }

    private void logFailedToPutValueToCache(String name, Exception e) {
        log.error("Failed to put value to cache: {}", name, e);
    }

    private void logStartingCacheStorage(String name, String redisKey, Duration ttl, byte[] value) {
        log.debug(
                "Starting cache storage: cacheName={}, key={}, ttl={}, dataSize={} bytes",
                name,
                redisKey,
                ttl,
                value.length);
    }

    private void logSuccessfullyStoredCacheDataWithTtl(
            String name,
            String redisKey,
            TtlCalculationResult ttlResult,
            Object deserializedValue) {
        log.debug(
                "Successfully stored cache data with TTL: cacheName={}, key={}, ttl={}s, fromContext={}, isNull={}",
                name,
                redisKey,
                ttlResult.finalTtl,
                ttlResult.fromContext,
                deserializedValue == null);
    }

    private void logSuccessfullyStoredPermanentCacheData(
            String name, String redisKey, Object deserializedValue) {
        log.debug(
                "Successfully stored permanent cache data: cacheName={}, key={}, isNull={}",
                name,
                redisKey,
                deserializedValue == null);
    }

    private void logSyncModeForConditionalCacheStorage(String name, String redisKey) {
        log.debug(
                "Using sync mode for conditional cache storage: cacheName={}, key={}",
                name,
                redisKey);
    }

    private void logStartingConditionalCacheStorage(
            String name, String redisKey, Duration ttl, byte[] value) {
        log.debug(
                "Starting conditional cache storage: cacheName={}, key={}, ttl={}, dataSize={} bytes",
                name,
                redisKey,
                ttl,
                value.length);
    }

    private void logCacheDataExistsAndNotExpired(String name, String redisKey) {
        log.debug(
                "Cache data exists and not expired, returning existing value: cacheName={}, key={}",
                name,
                redisKey);
    }

    private void logSkippingNullValueCachingInPutIfAbsent(String name, String redisKey) {
        log.debug(
                "Skipping null value caching in putIfAbsent (cacheNullValues=false): cacheName={}, key={}",
                name,
                redisKey);
    }

    private void logAttemptingConditionalStorageWithTtl(
            String name,
            String redisKey,
            TtlCalculationResult ttlResult,
            Object deserializedValue) {
        log.debug(
                "Attempting conditional storage with TTL: cacheName={}, key={}, ttl={}s, fromContext={}, isNull={}",
                name,
                redisKey,
                ttlResult.finalTtl,
                ttlResult.fromContext,
                deserializedValue == null);
    }

    private void logAttemptingConditionalStorageWithoutTtl(
            String name, String redisKey, Object deserializedValue) {
        log.debug(
                "Attempting conditional storage without TTL: cacheName={}, key={}, isNull={}",
                name,
                redisKey,
                deserializedValue == null);
    }

    private void logConditionalStorageSucceeded(String name, String redisKey) {
        log.debug("Conditional storage succeeded: cacheName={}, key={}", name, redisKey);
    }

    private void logConditionalStorageFailed(String name, String redisKey) {
        log.debug(
                "Conditional storage failed, retrieving existing value: cacheName={}, key={}",
                name,
                redisKey);
    }

    private void logFailedToPutIfAbsentValueToCache(String name, Exception e) {
        log.error("Failed to putIfAbsent value to cache: {}", name, e);
    }

    private void logStartingCacheDataRemoval(String name, String redisKey) {
        log.debug("Starting cache data removal: cacheName={}, key={}", name, redisKey);
    }

    private void logCacheDataRemovalCompleted(String name, String redisKey, Boolean deleted) {
        log.debug(
                "Cache data removal completed: cacheName={}, key={}, deleted={}",
                name,
                redisKey,
                deleted);
    }

    private void logFailedToRemoveValueFromCache(String name, Exception e) {
        log.error("Failed to remove value from cache: {}", name, e);
    }

    private void logStartingBatchCacheCleanup(String name, String keyPattern) {
        log.debug("Starting batch cache cleanup: cacheName={}, pattern={}", name, keyPattern);
    }

    private void logFoundMatchingCacheKeys(String name, String keyPattern, Set<String> keys) {
        log.debug(
                "Found matching cache keys: cacheName={}, pattern={}, count={}",
                name,
                keyPattern,
                keys.size());
    }

    private void logBatchCacheCleanupCompleted(String name, String keyPattern, Long deleteCount) {
        log.debug(
                "Batch cache cleanup completed: cacheName={}, pattern={}, deletedCount={}",
                name,
                keyPattern,
                deleteCount);
    }

    private void logNoMatchingCacheKeysFound(String name, String keyPattern) {
        log.debug("No matching cache keys found: cacheName={}, pattern={}", name, keyPattern);
    }

    private void logFailedToCleanCache(String name, Exception e) {
        log.error("Failed to clean cache: {}", name, e);
    }

    private void logStartingCacheStatisticsCleanup(String name) {
        log.debug("Starting cache statistics cleanup: cacheName={}", name);
    }

    private void logCacheStatisticsCleanupCompleted(String name) {
        log.debug("Cache statistics cleanup completed: cacheName={}", name);
    }

    private void logBloomFilterRejects(String name, String redisKey) {
        log.debug(
                "Bloom filter rejected (key does not exist): cacheName={}, key={}", name, redisKey);
    }

    private void logBloomFilterClearedWithCache(String name) {
        log.debug("Bloom filter cleared along with cache: cacheName={}", name);
    }

    private void logSyncPreRefreshTriggered(String name, String redisKey) {
        log.info(
                "Synchronous pre-refresh triggered, returning null to trigger cache miss: cacheName={}, key={}",
                name,
                redisKey);
    }

    private void logAsyncPreRefreshTriggered(String name, String redisKey) {
        log.info(
                "Asynchronous pre-refresh triggered, returning old value and refreshing cache in background: cacheName={}, key={}",
                name,
                redisKey);
    }

    private void logAsyncPreRefreshCacheDeleted(String name, String redisKey) {
        log.debug(
                "Asynchronous pre-refresh successfully deleted cache: cacheName={}, key={}",
                name,
                redisKey);
    }

    private void logAsyncPreRefreshFailed(String name, String redisKey, Exception e) {
        log.error("Asynchronous pre-refresh failed: cacheName={}, key={}", name, redisKey, e);
    }

    /** TTL计算结果 */
    private record TtlCalculationResult(long finalTtl, boolean shouldApply, boolean fromContext) {}
}
