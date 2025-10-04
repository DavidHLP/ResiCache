package com.david.spring.cache.redis.core.writer;

import com.david.spring.cache.redis.core.writer.chain.CacheOperation;
import com.david.spring.cache.redis.core.writer.chain.handler.CacheContext;
import com.david.spring.cache.redis.core.writer.chain.handler.CacheHandlerChain;
import com.david.spring.cache.redis.core.writer.chain.CacheHandlerChainFactory;
import com.david.spring.cache.redis.core.writer.chain.handler.CacheResult;
import com.david.spring.cache.redis.core.writer.support.TypeSupport;
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
import java.util.concurrent.CompletableFuture;

/**
 * Redis 增强缓存写入器（基于责任链模式重构）
 *
 * <p>核心功能：
 * - 使用责任链模式处理缓存操作
 * - 支持布隆过滤器（防止缓存穿透）
 * - 支持同步锁（防止缓存击穿）
 * - 支持 TTL 随机化（防止缓存雪崩）
 * - 支持缓存预刷新
 * - 支持空值缓存
 *
 * <p>责任链顺序：
 * BloomFilterHandler → SyncLockHandler → TtlHandler → NullValueHandler → ActualCacheHandler
 */
@Slf4j
@RequiredArgsConstructor
public class RedisProCacheWriter implements RedisCacheWriter {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ValueOperations<String, Object> valueOperations;
    private final CacheStatisticsCollector statistics;
    private final RedisCacheRegister redisCacheRegister;
    private final TypeSupport typeSupport;
    private final CacheHandlerChainFactory chainFactory;

    /** 缓存的责任链实例（单例，避免每次创建） */
    private volatile CacheHandlerChain cachedChain;

    @Override
    @Nullable
    public byte[] get(@NonNull String name, @NonNull byte[] key) {
        return get(name, key, null);
    }

    @Override
    @Nullable
    public byte[] get(@NonNull String name, @NonNull byte[] key, @Nullable Duration ttl) {
        String redisKey = typeSupport.bytesToString(key);
        String actualKey = extractActualKey(name, redisKey);

        // 构建上下文
        CacheContext context = buildContext(
                CacheOperation.GET,
                name,
                redisKey,
                actualKey,
                null,
                ttl);

        // 执行责任链（使用缓存的 chain 实例）
        CacheResult result = getChain().execute(context);

        return result.getResultBytes();
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
        String redisKey = typeSupport.bytesToString(key);
        String actualKey = extractActualKey(name, redisKey);

        // 反序列化值
        Object deserializedValue = typeSupport.deserializeFromBytes(value);

        // 构建上下文（带操作配置）
        CacheContext context = CacheContext.builder()
                .operation(CacheOperation.PUT)
                .cacheName(name)
                .redisKey(redisKey)
                .actualKey(actualKey)
                .valueBytes(value)
                .deserializedValue(deserializedValue)
                .ttl(ttl)
                .cacheOperation(operation)
                .build();

        // 执行责任链（使用缓存的 chain 实例）
        getChain().execute(context);
    }

    @Override
    public void put(
            @NonNull String name,
            @NonNull byte[] key,
            @NonNull byte[] value,
            @Nullable Duration ttl) {
        String redisKey = typeSupport.bytesToString(key);
        String actualKey = extractActualKey(name, redisKey);

        // 反序列化值
        Object deserializedValue = typeSupport.deserializeFromBytes(value);

        // 构建上下文
        CacheContext context = buildContext(
                CacheOperation.PUT,
                name,
                redisKey,
                actualKey,
                value,
                ttl);
        context.setDeserializedValue(deserializedValue);

        // 执行责任链（使用缓存的 chain 实例）
        getChain().execute(context);
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
        String redisKey = typeSupport.bytesToString(key);
        String actualKey = extractActualKey(name, redisKey);

        // 反序列化值
        Object deserializedValue = typeSupport.deserializeFromBytes(value);

        // 构建上下文
        CacheContext context = buildContext(
                CacheOperation.PUT_IF_ABSENT,
                name,
                redisKey,
                actualKey,
                value,
                ttl);
        context.setDeserializedValue(deserializedValue);

        // 执行责任链（使用缓存的 chain 实例）
        CacheResult result = getChain().execute(context);

        return result.getResultBytes();
    }

    @Override
    public void remove(@NonNull String name, @NonNull byte[] key) {
        String redisKey = typeSupport.bytesToString(key);
        String actualKey = extractActualKey(name, redisKey);

        // 构建上下文
        CacheContext context = buildContext(
                CacheOperation.REMOVE,
                name,
                redisKey,
                actualKey,
                null,
                null);

        // 执行责任链（使用缓存的 chain 实例）
        getChain().execute(context);
    }

    @Override
    public void clean(@NonNull String name, @NonNull byte[] pattern) {
        String keyPattern = typeSupport.bytesToString(pattern);
        String actualKey = extractActualKey(name, keyPattern);

        // 构建上下文
        CacheContext context = buildContext(
                CacheOperation.CLEAN,
                name,
                keyPattern,
                actualKey,
                null,
                null);
        context.setKeyPattern(keyPattern);

        // 执行责任链（使用缓存的 chain 实例）
        getChain().execute(context);
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
                redisTemplate,
                valueOperations,
                cacheStatisticsCollector,
                redisCacheRegister,
                typeSupport,
                chainFactory);
    }

    @Override
    @NonNull
    public CacheStatistics getCacheStatistics(@NonNull String cacheName) {
        return statistics.getCacheStatistics(cacheName);
    }

    /**
     * 构建缓存上下文
     *
     * @param operation 操作类型
     * @param cacheName 缓存名称
     * @param redisKey Redis完整key
     * @param actualKey 实际key
     * @param valueBytes 值字节数组
     * @param ttl TTL
     * @return 缓存上下文
     */
    private CacheContext buildContext(
            CacheOperation operation,
            String cacheName,
            String redisKey,
            String actualKey,
            @Nullable byte[] valueBytes,
            @Nullable Duration ttl) {

        // 获取缓存操作配置
        RedisCacheableOperation cacheOperation =
                redisCacheRegister.getCacheableOperation(cacheName, actualKey);

        return CacheContext.builder()
                .operation(operation)
                .cacheName(cacheName)
                .redisKey(redisKey)
                .actualKey(actualKey)
                .valueBytes(valueBytes)
                .ttl(ttl)
                .cacheOperation(cacheOperation)
                .build();
    }

    /**
     * 从完整的Redis key中提取实际的key部分
     * Redis key格式: {cacheName}::{actualKey}
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
        return redisKey;
    }

    // 以下方法用于向后兼容，如果有其他地方调用
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
     * 获取缓存的责任链实例（懒加载 + 双重检查锁）
     *
     * @return 责任链实例
     */
    private CacheHandlerChain getChain() {
        if (cachedChain == null) {
            synchronized (this) {
                if (cachedChain == null) {
                    cachedChain = chainFactory.createChain();
                    log.debug("Initialized cached handler chain for RedisProCacheWriter");
                }
            }
        }
        return cachedChain;
    }
}
