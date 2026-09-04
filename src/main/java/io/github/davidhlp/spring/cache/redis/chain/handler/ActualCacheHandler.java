package io.github.davidhlp.spring.cache.redis.chain.handler;

import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.model.*;


import io.github.davidhlp.spring.cache.redis.cache.model.CachedValue;
import io.github.davidhlp.spring.cache.redis.protection.nullvalue.NullValuePolicy;
import io.github.davidhlp.spring.cache.redis.protection.refresh.RefreshCancellation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.*;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实际缓存处理器
 *
 * <p>职责：
 * <ul>
 *   <li>执行实际的 Redis 缓存操作（GET/PUT/PUT_IF_ABSENT/REMOVE/CLEAN）</li>
 *   <li>不包含锁逻辑（由 SyncLockHandler 处理）</li>
 *   <li>提前过期逻辑由 EarlyExpirationHandler 处理</li>
 * </ul>
 */
@Slf4j
@Component
@HandlerPriority(HandlerOrder.ACTUAL_CACHE)
public class ActualCacheHandler extends AbstractCacheHandler {

    private static final int CLEAN_SCAN_COUNT = 512;
    private static final int CLEAN_DELETE_BATCH_SIZE = 256;

    private final RedisTemplate<String, Object> redisTemplate;
    private final ValueOperations<String, Object> valueOperations;
    private final NullValuePolicy nullValuePolicy;
    private final RefreshCancellation earlyExpirationExecutor;
    private final CacheErrorHandler errorHandler;
    public ActualCacheHandler(
            @Qualifier("redisCacheTemplate") RedisTemplate<String, Object> redisTemplate,
            ValueOperations<String, Object> valueOperations,
            NullValuePolicy nullValuePolicy,
            @Qualifier("earlyExpirationExecutor") RefreshCancellation earlyExpirationExecutor,
            CacheErrorHandler errorHandler) {
        this.redisTemplate = redisTemplate;
        this.valueOperations = valueOperations;
        this.nullValuePolicy = nullValuePolicy;
        this.earlyExpirationExecutor = earlyExpirationExecutor;
        this.errorHandler = errorHandler;
    }

    @Override
    protected boolean shouldHandle(CacheContext context) {
        return true;  // 总是处理，是责任链的最后一环
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        Assert.notNull(context, "CacheContext must not be null");
        Assert.notNull(context.getOperation(), "Cache operation must not be null");

        // 检查是否已被提前过期处理跳过（类型化 PrefetchDecision）
        PrefetchDecision prefetch = context.getPrefetchDecision();
        if (prefetch != null && prefetch.earlyExpirationSkipped()) {
            return HandlerResult.terminate(CacheResult.miss());
        }

        CacheResult result = dispatchOperation(context);

        // 终止责任链（结果仅通过 HandlerResult 返回）
        return HandlerResult.terminate(result);
    }

    /**
     * 根据操作类型分发
     */
    private CacheResult dispatchOperation(CacheContext context) {
        return switch (context.getOperation()) {
            case GET -> handleGet(context);
            case PUT -> handlePut(context);
            case PUT_IF_ABSENT -> handlePutIfAbsent(context);
            case REMOVE -> handleRemove(context);
            case CLEAN -> handleClean(context);
        };
    }

    // ==================== GET 操作 ====================

    /**
     * 处理 GET 操作
     * 
     * 注意：锁逻辑已由 SyncLockHandler 处理，这里直接执行 Redis 操作
     */
    private CacheResult handleGet(CacheContext context) {
        Assert.hasText(context.getCacheName(), "Cache name must not be empty");
        Assert.hasText(context.getRedisKey(), "Redis key must not be empty");

        log.debug("Cache GET: cacheName={}, key={}", context.getCacheName(), context.getRedisKey());

        try {
            // 优先复用 EarlyExpirationHandler 预取的缓存值，避免双重 Redis GET（类型化）
            PrefetchDecision prefetchDecision = context.getPrefetchDecision();
            CachedValue cachedValue = prefetchDecision != null ? prefetchDecision.prefetchedValue() : null;
            if (cachedValue == null) {
                Object rawValue = valueOperations.get(context.getRedisKey());
                cachedValue = (rawValue instanceof CachedValue cv) ? cv : null;
            }

            if (isCacheHit(cachedValue)) {
                return processCacheHit(context, cachedValue);
            }

            log.debug("Cache miss: cacheName={}, key={}", context.getCacheName(), context.getRedisKey());
            return CacheResult.miss();

        } catch (Exception e) {
            return errorHandler.handleError(context.getOperation(), context.getCacheName(), context.getRedisKey(), e);
        }
    }

    /**
     * 处理缓存命中
     *
     * <p>提前过期检查由 EarlyExpirationHandler 完成，这里只处理正常的缓存命中。
     */
    private CacheResult processCacheHit(CacheContext context, CachedValue cachedValue) {
        log.debug("Cache hit: cacheName={}, key={}, remainingTtl={}s",
                  context.getCacheName(), context.getRedisKey(), cachedValue.getRemainingTtl());

        // 读路径默认不触发写操作，避免写放大。
        // 如需 TTI（读取刷新 TTL），应使用 Spring Data Redis 的 RedisCacheConfiguration.enableTimeToIdle()，
        // 由 Redis 6.2+ 的 GETEX 命令实现，无需重写 value。
        byte[] result = nullValuePolicy.toReturnValue(
            cachedValue.getValue(), context.getCacheName(), context.getRedisKey());

        return CacheResult.success(result);
    }

    /**
     * 判断是否为有效的缓存命中
     */
    private boolean isCacheHit(CachedValue cachedValue) {
        return cachedValue != null && !cachedValue.checkExpired();
    }

    // ==================== PUT 操作 ====================

    /**
     * 处理 PUT 操作
     *
     * 注意：锁逻辑已由 SyncLockHandler 处理
     */
    private CacheResult handlePut(CacheContext context) {
        Assert.hasText(context.getCacheName(), "Cache name must not be empty");
        Assert.hasText(context.getRedisKey(), "Redis key must not be empty");

        TtlDecision ttl = context.getTtlDecision();
        log.debug("Cache PUT: cacheName={}, key={}, shouldApplyTtl={}, finalTtl={}",
                  context.getCacheName(), context.getRedisKey(),
                  ttl != null && ttl.shouldApplyTtl(),
                  ttl != null ? ttl.finalTtl() : -1L);

        try {
            // 取消可能的异步提前过期任务
            earlyExpirationExecutor.cancel(context.getRedisKey());

            // 解析存储意图（NullDecision 的 storeValue + TtlDecision 的 finalTtl → CachedValue + 可选 TTL，
            // 重载选择 / -1 哨兵 / Duration 映射全部收口在 StoreIntent 内）
            StoreIntent intent = resolveStoreIntent(context, resolveStoreValue(context));
            intent.applyPut(valueOperations, context.getRedisKey());

            log.debug("Cache PUT success: cacheName={}, key={}", context.getCacheName(), context.getRedisKey());

            return CacheResult.success();

        } catch (Exception e) {
            return errorHandler.handleError(context.getOperation(), context.getCacheName(), context.getRedisKey(), e);
        }
    }

    // ==================== PUT_IF_ABSENT 操作 ====================

    /**
     * 处理 PUT_IF_ABSENT 操作
     *
     * <p>直接使用 SETNX（setIfAbsent）保证原子性，避免先 GET 再 SET 的 TOCTOU 竞态。
     */
    private CacheResult handlePutIfAbsent(CacheContext context) {
        Assert.hasText(context.getCacheName(), "Cache name must not be empty");
        Assert.hasText(context.getRedisKey(), "Redis key must not be empty");

        log.debug("Cache PUT_IF_ABSENT: cacheName={}, key={}", context.getCacheName(), context.getRedisKey());

        try {
            // 解析存储意图 + 原子条件写入（SETNX 保证不存在时才写入，消除 TOCTOU 竞态；
            // 重载选择 / -1 哨兵 / Duration 映射全部收口在 StoreIntent 内）
            StoreIntent intent = resolveStoreIntent(context, resolveStoreValue(context));
            if (intent.applyPutIfAbsent(valueOperations, context.getRedisKey())) {
                log.debug("Cache PUT_IF_ABSENT success: cacheName={}, key={}",
                          context.getCacheName(), context.getRedisKey());
                return CacheResult.inserted();
            }

            // SETNX 失败说明 key 已存在，读取当前值返回
            log.debug("Cache PUT_IF_ABSENT: key already exists, returning existing value: cacheName={}, key={}",
                      context.getCacheName(), context.getRedisKey());
            CachedValue existingValue = (CachedValue) valueOperations.get(context.getRedisKey());
            if (existingValue != null) {
                byte[] result = nullValuePolicy.toReturnValue(
                    existingValue.getValue(), context.getCacheName(), context.getRedisKey());
                return CacheResult.existing(result);
            }

            return CacheResult.existing(null);

        } catch (Exception e) {
            return errorHandler.handleError(context.getOperation(), context.getCacheName(), context.getRedisKey(), e);
        }
    }

    // ==================== REMOVE 操作 ====================

    /**
     * 处理 REMOVE 操作
     */
    private CacheResult handleRemove(CacheContext context) {
        Assert.hasText(context.getCacheName(), "Cache name must not be empty");
        Assert.hasText(context.getRedisKey(), "Redis key must not be empty");

        log.debug("Cache REMOVE: cacheName={}, key={}", context.getCacheName(), context.getRedisKey());

        try {
            Boolean deleted = redisTemplate.delete(context.getRedisKey());

            log.debug("Cache REMOVE completed: cacheName={}, key={}, deleted={}",
                      context.getCacheName(), context.getRedisKey(), deleted);

            return CacheResult.success();

        } catch (Exception e) {
            return errorHandler.handleError(context.getOperation(), context.getCacheName(), context.getRedisKey(), e);
        }
    }

    // ==================== CLEAN 操作 ====================

    /**
     * 处理 CLEAN 操作（批量清理）
     */
    private CacheResult handleClean(CacheContext context) {
        Assert.hasText(context.getCacheName(), "Cache name must not be empty");

        String keyPattern = context.getKeyPattern();
        Assert.hasText(keyPattern, "Key pattern must not be empty");

        log.debug("Cache CLEAN: cacheName={}, pattern={}", context.getCacheName(), keyPattern);

        AtomicLong totalDeleted = new AtomicLong();
        try {

            redisTemplate.execute((RedisCallback<Void>) connection -> {
                ScanOptions scanOptions = ScanOptions.scanOptions()
                        .match(keyPattern)
                        .count(CLEAN_SCAN_COUNT)
                        .build();

                List<byte[]> batch = new ArrayList<>(CLEAN_DELETE_BATCH_SIZE);

                try (Cursor<byte[]> cursor = connection.keyCommands().scan(scanOptions)) {
                    while (cursor.hasNext()) {
                        batch.add(cursor.next());
                        if (batch.size() >= CLEAN_DELETE_BATCH_SIZE) {
                            long removed = removeBatch(connection, batch);
                            totalDeleted.addAndGet(removed);
                            batch.clear();
                        }
                    }
                    // 处理剩余的
                    if (!batch.isEmpty()) {
                        long removed = removeBatch(connection, batch);
                        totalDeleted.addAndGet(removed);
                        batch.clear();
                    }
                } catch (Exception scanException) {
                    throw new IllegalStateException(
                        String.format("Failed to scan keys: cacheName=%s, pattern=%s",
                                      context.getCacheName(), keyPattern), scanException);
                }
                return null;
            });

            long deletedTotal = totalDeleted.get();

            log.debug("Cache CLEAN completed: cacheName={}, pattern={}, deletedCount={}",
                      context.getCacheName(), keyPattern, deletedTotal);

            return CacheResult.success();

        } catch (Exception e) {
            String failureKind = totalDeleted.get() > 0 ? "PARTIAL_CLEAN" : "REDIS";
            return errorHandler.handleError(
                    context.getOperation(), context.getCacheName(), keyPattern, failureKind, e);
        }
    }

    /**
     * 批量删除
     */
    private long removeBatch(RedisConnection connection, List<byte[]> batch) {
        if (batch.isEmpty()) {
            return 0L;
        }
        byte[][] keys = batch.toArray(new byte[0][]);
        try {
            Long removed = connection.keyCommands().unlink(keys);
            if (removed != null) {
                return removed;
            }
        } catch (Exception ex) {
            log.trace("UNLINK not supported, falling back to DEL for batchSize={}", batch.size(), ex);
        }
        Long deleted = connection.keyCommands().del(keys);
        return deleted != null ? deleted : 0L;
    }

    // ==================== 存储意图解析（PUT / PUT_IF_ABSENT 共享） ====================

    /**
     * 存储意图 — 把 chain 的 {@link NullDecision} + {@link TtlDecision} 解析为一个不可变的
     * "写什么 + 写多久"决议物，是 PUT / PUT_IF_ABSENT 两个写路径共享的深模块。
     *
     * <p>封装的复杂度：
     * <ol>
     *   <li>Spring Data Redis 的 {@code set} / {@code setIfAbsent} 各有「带 Duration」与
     *       「不带 Duration」两条重载；{@code ttl == null}（永久缓存）走无 Duration 重载、
     *       {@code ttl != null} 走三参重载。重载选择在本记录内部收口，调用方只看到
     *       {@link #applyPut} / {@link #applyPutIfAbsent} 两个语义方法。</li>
     *   <li>{@link CachedValue#of(Object, long)} 的 {@code -1} 永久缓存哨兵仅本记录产生
     *       （skipped / TtlDecision 缺席分支），避免 {@code -1} 字面量散落到两个 handle 方法。</li>
     *   <li>{@code Duration.ofSeconds(finalTtl)} 的单位映射仅本记录产生。</li>
     * </ol>
     *
     * <p><b>deepening 理由（vs 内联）</b>：本记录 + 两个 {@code resolve} helper 把两个写路径
     * 共享的 TTL 分支 + CachedValue 构造 + 重载选择逻辑<b>浓缩</b>到一处（deletion test：
     * 删掉后内联回去 = 重复回归，复杂度上升而非下降 → 真 seam）。「存储意图」这个隐含概念
     * 获得命名与 locality，未来新增写路径可直接复用。
     *
     * <p>本记录为 ActualCacheHandler 私有：当前仅 PUT / PUT_IF_ABSENT 两个消费者，
     * 未达提升为顶层类型的必要性（YAGNI）。
     *
     * @param cachedValue 已封装好的存储值（含 finalTtl / -1 哨兵）
     * @param ttl         应用 TTL 时的 {@link Duration}；{@code null} 表示永久缓存（走无 Duration 重载）
     */
    private record StoreIntent(CachedValue cachedValue, @Nullable Duration ttl) {

        /** PUT 路径：据 ttl 选 {@code ValueOperations.set} 重载（无返回值）。 */
        void applyPut(ValueOperations<String, Object> ops, String key) {
            if (ttl != null) {
                ops.set(key, cachedValue, ttl);
            } else {
                ops.set(key, cachedValue);
            }
        }

        /** PUT_IF_ABSENT 路径：据 ttl 选 {@code ValueOperations.setIfAbsent} 重载；返回是否写入成功。 */
        boolean applyPutIfAbsent(ValueOperations<String, Object> ops, String key) {
            Boolean success = (ttl != null)
                    ? ops.setIfAbsent(key, cachedValue, ttl)
                    : ops.setIfAbsent(key, cachedValue);
            return Boolean.TRUE.equals(success);
        }
    }

    /**
     * 解析存储值 — {@link NullDecision} 在场且 {@link NullDecision#storeValue()} 非 null 时
     * 用决策值；否则（NullDecision 缺席，或显式 {@link NullDecision#of(Object) of(null)}
     * 表示「无需转换」）沿用 {@link CacheContext#getDeserializedValue() deserializedValue}。
     *
     * <p>handlePut 与 handlePutIfAbsent 共享相同的 null-fallback 逻辑。
     */
    private Object resolveStoreValue(CacheContext context) {
        NullDecision nullDecision = context.getNullDecision();
        if (nullDecision != null && nullDecision.storeValue() != null) {
            return nullDecision.storeValue();
        }
        return context.getDeserializedValue();
    }

    /**
     * 解析存储意图 — {@link TtlDecision} 在场且 {@link TtlDecision#shouldApplyTtl()} 为真时
     * 物化为带 TTL 的 {@link StoreIntent}（CachedValue 携 finalTtl、Duration 为 ofSeconds(finalTtl)）；
     * 否则（TtlDecision 缺席，或 {@link TtlDecision#skipped() skipped}）物化为永久缓存
     * （CachedValue 携 -1、Duration 为 null → 调用方走无 TTL 重载）。
     *
     * <p>handlePut 与 handlePutIfAbsent 共享 TTL 决策物化为存储意图的逻辑；本方法 +
     * {@link StoreIntent} 把它浓缩为单点，调用方仅剩"调 applyPut / applyPutIfAbsent"的 1-liner。
     */
    private StoreIntent resolveStoreIntent(CacheContext context, Object storeValue) {
        TtlDecision ttl = context.getTtlDecision();
        if (ttl != null && ttl.shouldApplyTtl()) {
            return new StoreIntent(CachedValue.of(storeValue, ttl.finalTtl()),
                                   Duration.ofSeconds(ttl.finalTtl()));
        }
        return new StoreIntent(CachedValue.of(storeValue, -1L), null);
    }
}
