package io.github.davidhlp.spring.cache.redis.cache;







import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

/**
 * Redis 增强缓存写入器（基于责任链模式重构）
 *
 * <p>核心功能： - 使用责任链模式处理缓存操作 - 支持布隆过滤器（防止缓存穿透） - 支持同步锁（防止缓存击穿） - 支持 TTL 随机化（防止缓存雪崩） - 支持缓存提前过期 -
 * 支持空值缓存
 *
 * <p>责任链顺序(由 {@link io.github.davidhlp.spring.cache.redis.chain.HandlerOrder} 定义)：
 * BloomFilterHandler → SyncLockHandler → EarlyExpirationHandler → TtlHandler →
 * NullValueHandler → ActualCacheHandler
 *
 * <p>本类持有单一 {@link CacheOperationResolver} seam —— 消除两处镜像
 * "读 ThreadLocal key → 查 register"协议(本类 {@code resolveOperation} 与
 * {@code RedisProCache} 的 lookup)漂移风险;
 * {@code resolveOperation} 为 1 行委派。
 */
@Slf4j
class RedisProCacheWriter implements RedisCacheWriter {

    private final CacheOperationResolver operationResolver;
    private final CacheStatisticsCollector statistics;
    private final CacheValueCodec valueCodec;
    private final CacheHandlerChainFactory chainFactory;

    /** 缓存的责任链实例 */
    private final CacheHandlerChain cachedChain;

    /**
     * 构造函数，初始化缓存责任链。
     */
    public RedisProCacheWriter(CacheStatisticsCollector statistics,
                               CacheValueCodec valueCodec,
                               CacheHandlerChainFactory chainFactory,
                               CacheOperationResolver operationResolver) {
        this.statistics = statistics;
        this.valueCodec = valueCodec;
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
        CacheResult result = executeChain(CacheOperation.GET, name, key, null, ttl);
        byte[] resultBytes = result.resultBytes();
        recordGetStatistics(name, result, resultBytes);
        return resultBytes;
    }

    /**
     * Read-through loader 入口 —— availability-first(ADR-0001 §13)。
     *
     * <p>{@code RedisProCache.get(key, loader)} 与本方法都委派
     * {@link LoaderOrchestrator#readThrough} 的同一条「读 → 回源 → 写回」协议。
     * 本入口只提供 writer 的字节读写适配,并保留低层 SPI 的 loader 异常原样传播契约;
     * {@link org.springframework.cache.Cache#getNativeCache()} 是公开方法,调用方拿到
     * {@code RedisCacheWriter} 后可以直接走这里。
     *
     * <p>{@code cacheTti} 按 ResiCache 的既有语义保持忽略:链 GET 不做 TTI 刷新,
     * 命中/未命中均按普通读处理。刷新 TTL 会引入本库刻意避免的写放大。
     *
     * @param name           缓存名
     * @param key            Redis key 字节
     * @param loaderSupplier loader 字节提供者(Spring 契约;loader 异常原样传播)
     * @param ttl            TTL
     * @param cacheTti       是否 time-to-idle(本实现保持忽略,不刷新 TTL)
     * @return 缓存命中或 loader 产出的字节;缓存 miss 且 loader 产出 null 时为 null
     */
    @Override
    @Nullable
    public byte[] get(@NonNull String name, @NonNull byte[] key,
                      @NonNull java.util.function.Supplier<byte[]> loaderSupplier,
                      @Nullable Duration ttl, boolean cacheTti) {
        LoaderOrchestrator.LoadOutcome<byte[]> outcome = LoaderOrchestrator.readThrough(
                name,
                () -> get(name, key, ttl),
                cached -> cached != null,
                cached -> cached,
                loaderSupplier::get,
                loaded -> put(name, key, loaded, ttl),
                cause -> cause);
        if (outcome instanceof LoaderOrchestrator.Loaded<?> loaded) {
            return (byte[]) loaded.value();
        }
        if (outcome instanceof LoaderOrchestrator.LoadedWithWriteBackFailure<?> loaded) {
            return (byte[]) loaded.value();
        }
        if (outcome instanceof LoaderOrchestrator.LoadFailed<?> failed) {
            return throwRawLoaderFailure(failed.cause());
        }
        return null;
    }
    private static byte[] throwRawLoaderFailure(Throwable cause) {
        RedisProCacheWriter.<RuntimeException>throwUnchecked(cause);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable cause) throws E {
        throw (E) cause;
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

    @Override
    public void put(
            @NonNull String name,
            @NonNull byte[] key,
            @NonNull byte[] value,
            @Nullable Duration ttl) {
        CacheResult result = executeChain(CacheOperation.PUT, name, key, value, ttl);
        CacheErrorHandler.finalizeFailure(CacheOperation.PUT, name, result);
        if (result.isSuccess()) {
            statistics.incPuts(name);
        }
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
        CacheErrorHandler.finalizeFailure(CacheOperation.PUT_IF_ABSENT, name, result);
        if (result.outcome() == CacheResult.Outcome.INSERTED) {
            statistics.incPuts(name);
        }
        return result.resultBytes();
    }

    @Override
    public void evict(@NonNull String name, @NonNull byte[] key) {
        // SDR 4.0 abstract entry point (replaces the deprecated remove default);
        // route through the same responsibility-chain logic.
        CacheResult result = executeChain(CacheOperation.REMOVE, name, key, null, null);
        CacheErrorHandler.finalizeFailure(CacheOperation.REMOVE, name, result);
        if (result.isSuccess()) {
            statistics.incDeletes(name);
        }
    }

    @Override
    public void clear(@NonNull String name, @NonNull byte[] pattern) {
        // SDR 4.0 abstract entry point (replaces the deprecated clean default);
        // route through the same responsibility-chain logic.
        String keyPattern = new String(pattern, StandardCharsets.UTF_8);
        String actualKey = extractActualKey(name, keyPattern);

        // 构建上下文 —— keyPattern 前置进 buildContext,避免后置 mutate
        CacheContext context = buildContext(
                CacheOperation.CLEAN, name, keyPattern, actualKey,
                null, null, null, resolveOperation(name, CacheOperation.CLEAN), keyPattern);

        CacheResult result = executeContext(context);
        CacheErrorHandler.finalizeFailure(CacheOperation.CLEAN, name, result);
        if (result.isSuccess()) {
            recordCleanDeletes(name, result.deletedCount());
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
                cacheStatisticsCollector,
                valueCodec,
                chainFactory,
                operationResolver);
    }

    @Override
    @NonNull
    public CacheStatistics getCacheStatistics(@NonNull String cacheName) {
        return statistics.getCacheStatistics(cacheName);
    }

    /**
     * 解析方法级策略(布隆/同步锁/TTL/空值等)—— 1 行委派。
     *
     * <p>委派 {@link CacheOperationResolver#resolve(String, CacheOperation)};{@code operationResolver} 为 null
     * 时直接返回 null(测试场景关闭元数据查找)。
     *
     * @param cacheName 缓存名称
     * @param operation 当前链侧操作(决定查询的注册命名空间)
     * @return 命中的策略视图;无元数据或未命中返回 null
     */
    @Nullable
    private CachePolicyView.Source resolveOperation(@NonNull String cacheName, CacheOperation operation) {
        return operationResolver == null ? null : operationResolver.resolve(cacheName, operation);
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
     * @param cacheOperation 已解析的方法级策略视图(可为 null)
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
            @Nullable CachePolicyView.Source cacheOperation,
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
        String redisKey = new String(key, StandardCharsets.UTF_8);
        String actualKey = extractActualKey(name, redisKey);
        Object deserializedValue =
                valueBytes != null ? valueCodec.fromValueBytes(valueBytes) : null;
        CacheContext context = buildContext(
                operation, name, redisKey, actualKey, valueBytes, deserializedValue, ttl,
                resolveOperation(name, operation), null);
        return executeContext(context);
    }

    private CacheResult executeContext(CacheContext context) {
        return cachedChain.execute(context);
    }

    private void recordGetStatistics(
            String cacheName, CacheResult result, @Nullable byte[] resultBytes) {
        if (!result.isSuccess()) {
            return;
        }
        statistics.incGets(cacheName);
        if (resultBytes == null) {
            statistics.incMisses(cacheName);
        } else {
            statistics.incHits(cacheName);
        }
    }

    private void recordCleanDeletes(String cacheName, long deletedCount) {
        long remaining = deletedCount;
        while (remaining > Integer.MAX_VALUE) {
            statistics.incDeletesBy(cacheName, Integer.MAX_VALUE);
            remaining -= Integer.MAX_VALUE;
        }
        statistics.incDeletesBy(cacheName, (int) remaining);
    }

}
