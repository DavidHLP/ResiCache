package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.cache.loader.CacheOperationResolver;
import io.github.davidhlp.spring.cache.redis.chain.metadata.MethodSnapshot;
import io.github.davidhlp.spring.cache.redis.cache.model.CacheKeys;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChain;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChainFactory;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheInput;
import io.github.davidhlp.spring.cache.redis.serialization.TypeSupport;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import org.slf4j.MDC;

import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.util.Map;
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
 *
 * <p>本类持有单一 {@link CacheOperationResolver} seam —— 消除 {@link #resolveOperation(String)}
 * 与 {@link RedisProCache#lookupOperation()} 的"读 ThreadLocal key → 查 register"镜像协议漂移风险;
 * {@code resolveOperation} 为 1 行委派。
 */
@Slf4j
public class RedisProCacheWriter implements RedisCacheWriter {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ValueOperations<String, Object> valueOperations;
    private final CacheOperationResolver operationResolver;
    private final CacheStatisticsCollector statistics;
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
                               TypeSupport typeSupport,
                               CacheHandlerChainFactory chainFactory,
                               CacheOperationResolver operationResolver) {
        this.redisTemplate = redisTemplate;
        this.valueOperations = valueOperations;
        this.statistics = statistics;
        this.typeSupport = typeSupport;
        this.chainFactory = chainFactory;
        this.operationResolver = operationResolver;
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
    public byte[] get(
            @NonNull String name, @NonNull byte[] key, @Nullable Duration ttl) {
        return executeChain(CacheOperation.GET, name, key, null, ttl).getResultBytes();
    }

    @Override
    public boolean supportsAsyncRetrieve() {
        // retrieve()/store() 经 resolver.runWithSnapshot 透传方法级元数据
        // (布隆/同步锁/TTL/空值等 operation 配置)+ MDC 到 commonPool 异步线程,让 SDR 走
        // 异步 retrieve 路径(性能优化)。边界管理归 MethodMetadataResolver。
        return true;
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
        MethodSnapshot snapshot = operationResolver == null ? null : operationResolver.capture();
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(
                () -> operationResolver == null
                        ? get(name, key, ttl)
                        : operationResolver.runWithSnapshot(
                                snapshot, mdcSnapshot, () -> get(name, key, ttl)));
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

        // 序列化错误在 Redis 链之前保留为 SerializationException,与 Redis failure
        // taxonomy 分离;链内 Redis failure 由 requireSuccessful 转换。
        Object deserializedValue = typeSupport.deserializeFromBytes(value);

        // 构建上下文(带操作配置)—— operation 已传入,直接走 buildContext,跳过 register 查询
        CacheContext context = buildContext(
                CacheOperation.PUT, name, redisKey, actualKey,
                value, deserializedValue, ttl, operation, null);

        CacheResult result = getChain().execute(context);
        requireSuccessful(CacheOperation.PUT, name, redisKey, result);
    }

    @Override
    public void put(
            @NonNull String name,
            @NonNull byte[] key,
            @NonNull byte[] value,
            @Nullable Duration ttl) {
        CacheResult result = executeChain(CacheOperation.PUT, name, key, value, ttl);
        requireSuccessful(CacheOperation.PUT, name, key, result);
    }

    @Override
    @NonNull
    public CompletableFuture<Void> store(
            @NonNull String name,
            @NonNull byte[] key,
            @NonNull byte[] value,
            @Nullable Duration ttl) {
        MethodSnapshot snapshot = operationResolver == null ? null : operationResolver.capture();
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        return CompletableFuture.runAsync(() -> {
            if (operationResolver == null) {
                put(name, key, value, ttl);
                return;
            }
            operationResolver.runWithSnapshot(snapshot, mdcSnapshot, () -> {
                put(name, key, value, ttl);
                return null;
            });
        });
    }

    @Override
    @Nullable
    public byte[] putIfAbsent(
            @NonNull String name,
            @NonNull byte[] key,
            @NonNull byte[] value,
            @Nullable Duration ttl) {
        CacheResult result = executeChain(CacheOperation.PUT_IF_ABSENT, name, key, value, ttl);
        requireSuccessful(CacheOperation.PUT_IF_ABSENT, name, key, result);
        return result.getResultBytes();
    }

    @Override
    public void remove(@NonNull String name, @NonNull byte[] key) {
        CacheResult result = executeChain(CacheOperation.REMOVE, name, key, null, null);
        requireSuccessful(CacheOperation.REMOVE, name, key, result);
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

        // 构建上下文 —— keyPattern 前置进 buildContext,避免后置 mutate
        CacheContext context = buildContext(
                CacheOperation.CLEAN, name, keyPattern, actualKey,
                null, null, null, resolveOperation(name), keyPattern);

        CacheResult result = getChain().execute(context);
        requireSuccessful(CacheOperation.CLEAN, name, keyPattern, result);
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
                typeSupport,
                chainFactory,
                operationResolver);
    }

    @Override
    @NonNull
    public CacheStatistics getCacheStatistics(@NonNull String cacheName) {
        return statistics.getCacheStatistics(cacheName);
    }

    /**
     * 解析方法级 operation 配置(布隆/同步锁/TTL/空值等)—— 1 行委派。
     *
     * <p>委派 {@link CacheOperationResolver#resolve(String)};{@code operationResolver} 为 null
     * 时直接返回 null(测试场景关闭元数据查找)。
     *
     * @param cacheName 缓存名称
     * @return 命中的 operation;无元数据或未命中返回 null
     */
    @Nullable
    private RedisCacheableOperation resolveOperation(@NonNull String cacheName) {
        return operationResolver == null ? null : operationResolver.resolve(cacheName);
    }

    /**
     * 统一的 CacheContext 构造 seam —— 5 个 SDR 入口(GET/PUT/PUT_IF_ABSENT/REMOVE/CLEAN)
     * 与带 operation 的 put 重载均经此构造。
     *
     * <p>cacheOperation 由调用方解析:executeChain/clean 走 {@link #resolveOperation} 查 register,
     * put 5参重载直接传入已持有的 operation。keyPattern 仅 CLEAN 操作非 null —— 作为
     * CacheContext direct field 前置设置,避免 clean 后置 mutate。
     *
     * @param operation 操作类型
     * @param cacheName 缓存名称
     * @param redisKey Redis 完整 key
     * @param actualKey 实际 key(去前缀)
     * @param valueBytes 值字节数组(读路径/REMOVE/CLEAN 为 null)
     * @param deserializedValue 反序列化后的值(同上为 null)
     * @param ttl TTL
     * @param cacheOperation 已解析的方法级 operation 配置(可为 null)
     * @param keyPattern CLEAN 的键模式(非 CLEAN 传 null)
     * @return 缓存上下文
     */
    private CacheContext buildContext(
            CacheOperation operation,
            @NonNull String cacheName,
            String redisKey,
            String actualKey,
            @Nullable byte[] valueBytes,
            @Nullable Object deserializedValue,
            @Nullable Duration ttl,
            @Nullable RedisCacheableOperation cacheOperation,
            @Nullable String keyPattern) {

        CacheContext context = CacheContext.of(CacheInput.builder()
                .operation(operation)
                .cacheName(cacheName)
                .redisKey(redisKey)
                .actualKey(actualKey)
                .valueBytes(valueBytes)
                .deserializedValue(deserializedValue)
                .ttl(ttl)
                .cacheOperation(cacheOperation)
                .build());
        if (keyPattern != null) {
            context.setKeyPattern(keyPattern);
        }
        return context;
    }

    /**
     * 从完整的 Redis key 中提取实际的 key 部分。键派生统一收口到 {@link CacheKeys},
     * 与 {@link RedisProCache} 的 loader 路径 bloom 查询同源,杜绝 actualKey/redisKey 漂移。
     *
     * @param cacheName 缓存名称
     * @param redisKey 完整的Redis key
     * @return 实际的key部分
     */
    private String extractActualKey(String cacheName, String redisKey) {
        return CacheKeys.fromRedisKey(cacheName, redisKey).actualKey();
    }

    /**
     * 同步执行责任链的统一入口(GET/PUT/PUT_IF_ABSENT/REMOVE):封装 key 解析 → 值反序列化 →
     * operation 解析(register 查询)→ 上下文构建 → 链执行。
     *
     * <p>GET / PUT_IF_ABSENT 消费返回字节;PUT / CLEAN 将失败结果转换为 typed
     * exception;REMOVE 记录失败并继续 best-effort。带 operation 的 put 重载与
     * clean 各自直接调 {@link #buildContext},不经此入口。
     *
     * @param operation 操作类型
     * @param name 缓存名称
     * @param key 原始 key 字节
     * @param valueBytes 值字节(读路径/REMOVE 为 null)
     * @param ttl TTL
     * @return 责任链执行结果
     */
    private CacheResult executeChain(
            CacheOperation operation,
            @NonNull String name,
            @NonNull byte[] key,
            @Nullable byte[] valueBytes,
            @Nullable Duration ttl) {
        String redisKey = typeSupport.bytesToString(key);
        String actualKey = extractActualKey(name, redisKey);
        Object deserializedValue =
                valueBytes != null ? typeSupport.deserializeFromBytes(valueBytes) : null;
        CacheContext context = buildContext(
                operation, name, redisKey, actualKey, valueBytes, deserializedValue, ttl,
                resolveOperation(name), null);
        return getChain().execute(context);
    }

    /**
     * 获取缓存的责任链实例（饿汉式单例）
     *
     * @return 责任链实例
     */
    private CacheHandlerChain getChain() {
        return cachedChain;
    }

    private void requireSuccessful(
            CacheOperation operation, String cacheName, byte[] key, CacheResult result) {
        if (!result.isSuccess()) {
            requireSuccessful(operation, cacheName, typeSupport.bytesToString(key), result);
        }
    }

    private void requireSuccessful(
            CacheOperation operation, String cacheName, String key, CacheResult result) {
        if (result.isSuccess()) {
            return;
        }
        if (operation == CacheOperation.REMOVE) {
            log.warn("Cache REMOVE failed; continuing best-effort: cacheName={}, key={}, kind={}, error={}",
                    cacheName,
                    key,
                    result.getFailureKind(),
                    result.getCause() == null ? null : result.getCause().getMessage());
            return;
        }
        throw new CacheOperationException(
                operation.name(),
                result.getFailureKind(),
                cacheName,
                key,
                result.getCause());
    }
}
