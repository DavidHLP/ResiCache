package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.holder.CacheOperationMetadataHolder;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChain;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChainFactory;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.serialization.TypeSupport;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import org.springframework.context.expression.AnnotatedElementKey;

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
 * <p>核心功能： - 使用责任链模式处理缓存操作 - 支持布隆过滤器（防止缓存穿透） - 支持同步锁（防止缓存击穿） - 支持 TTL 随机化（防止缓存雪崩） - 支持缓存提前过期 -
 * 支持空值缓存
 *
 * <p>责任链顺序： BloomFilterHandler → SyncLockHandler → TtlHandler → NullValueHandler →
 * ActualCacheHandler
 */
@Slf4j
public class RedisProCacheWriter implements RedisCacheWriter {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ValueOperations<String, Object> valueOperations;
    private final CacheStatisticsCollector statistics;
    private final RedisCacheRegister redisCacheRegister;
    private final TypeSupport typeSupport;
    private final CacheHandlerChainFactory chainFactory;

    /** 缓存的责任链实例 */
    private final CacheHandlerChain cachedChain;

    /**
     * 构造函数，初始化缓存责任链
     */
    public RedisProCacheWriter(RedisTemplate<String, Object> redisTemplate,
                               ValueOperations<String, Object> valueOperations,
                               CacheStatisticsCollector statistics,
                               RedisCacheRegister redisCacheRegister,
                               TypeSupport typeSupport,
                               CacheHandlerChainFactory chainFactory) {
        this.redisTemplate = redisTemplate;
        this.valueOperations = valueOperations;
        this.statistics = statistics;
        this.redisCacheRegister = redisCacheRegister;
        this.typeSupport = typeSupport;
        this.chainFactory = chainFactory;
        log.debug("Initializing handler chain for RedisProCacheWriter");
        this.cachedChain = chainFactory.createChain();
    }

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
        CacheContext context =
                buildContext(CacheOperation.GET, name, redisKey, actualKey, null, null, ttl);

        // 执行责任链（使用缓存的 chain 实例）
        CacheResult result = getChain().execute(context);

        return result.getResultBytes();
    }

    @Override
    public boolean supportsAsyncRetrieve() {
        // FIRE M3 (WS-1.1): 显式声明「不支持异步 retrieve」,锁定同步 get() 路径。
        // SDR 4.0.5 接口默认值已是 false(经 javap 验证 #supportsAsyncRetrieve → iconst_0);
        // 这里显式 return false 是【能力如实声明】+【防御性硬化】而非改变当前行为:
        //   本类的 retrieve()/store() 实现走 CompletableFuture.supplyAsync/runAsync(commonPool),
        //   会把责任链丢到 ForkJoinPool 线程执行,而 CacheOperationMetadataHolder 是 ThreadLocal ——
        //   commonPool 线程拿不到方法级元数据,@Cacheable 的 operation 配置(布隆/同步锁/TTL/空值
        //   等)会静默失效。故在 Path C(WS-1.3)补上 ThreadLocal snapshot/restore 之前,
        //   永不让 SDR 走异步 retrieve 路径(也防未来 SDR 版本把默认值翻成 true)。
        // Path C Step 6 落地后,此处恢复 true(届时 retrieve() 已带 snapshot,可在异步边界透传)。
        return false;
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
        CacheContext context =
                CacheContext.builder()
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
        CacheContext context =
                buildContext(CacheOperation.PUT, name, redisKey, actualKey, value, deserializedValue, ttl);

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
        CacheContext context =
                buildContext(CacheOperation.PUT_IF_ABSENT, name, redisKey, actualKey, value, deserializedValue, ttl);

        // 执行责任链（使用缓存的 chain 实例）
        CacheResult result = getChain().execute(context);

        return result.getResultBytes();
    }

    @Override
    public void remove(@NonNull String name, @NonNull byte[] key) {
        String redisKey = typeSupport.bytesToString(key);
        String actualKey = extractActualKey(name, redisKey);

        // 构建上下文
        CacheContext context =
                buildContext(CacheOperation.REMOVE, name, redisKey, actualKey, null, null, null);

        // 执行责任链（使用缓存的 chain 实例）
        getChain().execute(context);
    }

    @Override
    public void evict(@NonNull String name, @NonNull byte[] key) {
        // SDR 4.0 把 RedisCacheWriter.remove 重命名为 evict(boot4 新增的抽象方法);委托同一责任链逻辑
        remove(name, key);
    }

    @Override
    public void clean(@NonNull String name, @NonNull byte[] pattern) {
        String keyPattern = typeSupport.bytesToString(pattern);
        String actualKey = extractActualKey(name, keyPattern);

        // 构建上下文
        CacheContext context =
                buildContext(CacheOperation.CLEAN, name, keyPattern, actualKey, null, null, null);
        context.setKeyPattern(keyPattern);

        // 执行责任链（使用缓存的 chain 实例）
        getChain().execute(context);
    }

    @Override
    public void clear(@NonNull String name, @NonNull byte[] pattern) {
        // SDR 4.0 把 RedisCacheWriter.clean 重命名为 clear(boot4 新增的抽象方法);
        // 委托同一责任链逻辑,保持 clean/clear 行为一致。
        clean(name, pattern);
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
     * @param deserializedValue 反序列化后的值
     * @param ttl TTL
     * @return 缓存上下文
     */
    private CacheContext buildContext(
            CacheOperation operation,
            String cacheName,
            String redisKey,
            String actualKey,
            @Nullable byte[] valueBytes,
            @Nullable Object deserializedValue,
            @Nullable Duration ttl) {

        // 获取缓存操作配置（优先通过 AnnotatedElementKey 查找，匹配 Spring 的方法级元数据语义）
        AnnotatedElementKey elementKey = CacheOperationMetadataHolder.getCurrentKey();
        RedisCacheableOperation cacheOperation = null;
        if (elementKey != null) {
            cacheOperation = redisCacheRegister.getCacheableOperation(cacheName, elementKey);
        }
        if (cacheOperation == null) {
            log.debug("No metadata found via AnnotatedElementKey for cacheName={}, falling back to actualKey", cacheName);
        }

        return CacheContext.builder()
                .operation(operation)
                .cacheName(cacheName)
                .redisKey(redisKey)
                .actualKey(actualKey)
                .valueBytes(valueBytes)
                .deserializedValue(deserializedValue)
                .ttl(ttl)
                .cacheOperation(cacheOperation)
                .build();
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
        return redisKey;
    }

    // 以下方法用于向后兼容，如果有其他地方调用
    protected long getTtl(String redisKey) {
        Object value = valueOperations.get(redisKey);
        if (value instanceof CachedValue cachedValue) {
            return cachedValue.getTtl();
        }
        return -1;
    }

    protected long getExpiration(String redisKey) {
        return redisTemplate.getExpire(redisKey);
    }

    /**
     * 获取缓存的责任链实例（饿汉式单例）
     *
     * @return 责任链实例
     */
    private CacheHandlerChain getChain() {
        return cachedChain;
    }
}
