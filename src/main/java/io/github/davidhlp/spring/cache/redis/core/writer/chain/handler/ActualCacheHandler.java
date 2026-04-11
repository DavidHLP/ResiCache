package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.CachedValue;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.nullvalue.NullValuePolicy;
import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshSupport;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.*;
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
 *   <li>不包含锁逻辑（已移至 SyncLockHandler）</li>
 *   <li>预刷新逻辑由 PreRefreshHandler 处理</li>
 * </ul>
 *
 * <p>设计改进：
 * <ul>
 *   <li>原设计：锁逻辑、预刷新逻辑、实际操作混在一起，职责过重</li>
 *   <li>新设计：仅负责 Redis 操作，其他逻辑由专门的 Handler 处理</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@HandlerPriority(HandlerOrder.ACTUAL_CACHE)
public class ActualCacheHandler extends AbstractCacheHandler {

    private static final int CLEAN_SCAN_COUNT = 512;
    private static final int CLEAN_DELETE_BATCH_SIZE = 256;

    private final RedisTemplate<String, Object> redisTemplate;
    private final ValueOperations<String, Object> valueOperations;
    private final CacheStatisticsCollector statistics;
    private final NullValuePolicy nullValuePolicy;
    private final PreRefreshSupport preRefreshSupport;
    private final CacheErrorHandler errorHandler;

    @Override
    protected boolean shouldHandle(CacheContext context) {
        return true;  // 总是处理，是责任链的最后一环
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        Assert.notNull(context, "CacheContext must not be null");
        Assert.notNull(context.getOperation(), "Cache operation must not be null");

        // 检查是否已被预刷新处理跳过
        if (context.getAttribute(CacheContext.AttributeKey.PRE_REFRESH_SKIPPED, false)) {
            return HandlerResult.terminate(CacheResult.miss());
        }

        CacheResult result = dispatchOperation(context);

        // 保存最终结果
        context.getOutput().setFinalResult(result);

        // 终止责任链
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

        statistics.incGets(context.getCacheName());

        try {
            CachedValue cachedValue = (CachedValue) valueOperations.get(context.getRedisKey());

            if (isCacheHit(cachedValue)) {
                return processCacheHit(context, cachedValue);
            }

            log.debug("Cache miss: cacheName={}, key={}", context.getCacheName(), context.getRedisKey());
            statistics.incMisses(context.getCacheName());
            return CacheResult.miss();

        } catch (Exception e) {
            return errorHandler.handleGetError(context.getCacheName(), context.getRedisKey(), e);
        }
    }

    /**
     * 处理缓存命中
     *
     * <p>预刷新检查由 PreRefreshHandler 完成，这里只处理正常的缓存命中。
     */
    private CacheResult processCacheHit(CacheContext context, CachedValue cachedValue) {
        log.debug("Cache hit: cacheName={}, key={}, remainingTtl={}s",
                  context.getCacheName(), context.getRedisKey(), cachedValue.getRemainingTtl());

        statistics.incHits(context.getCacheName());

        // 更新访问时间
        cachedValue.updateAccess();
        updateTtlIfExists(context, cachedValue);

        // 转换返回值
        byte[] result = nullValuePolicy.toReturnValue(
            cachedValue.getValue(), context.getCacheName(), context.getRedisKey());

        return CacheResult.success(result);
    }

    /**
     * 更新 TTL（如果 key 仍存在）
     */
    private void updateTtlIfExists(CacheContext context, CachedValue cachedValue) {
        try {
            long remainingTtl = cachedValue.getRemainingTtl();
            if (remainingTtl >= 0) {
                valueOperations.setIfPresent(
                    context.getRedisKey(), cachedValue, Duration.ofSeconds(remainingTtl));
            } else {
                valueOperations.setIfPresent(context.getRedisKey(), cachedValue);
            }
        } catch (Exception e) {
            log.debug("Failed to update TTL: key={}", context.getRedisKey(), e);
        }
    }

    /**
     * 判断是否为有效的缓存命中
     */
    private boolean isCacheHit(CachedValue cachedValue) {
        return cachedValue != null && !cachedValue.isExpired();
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

        log.debug("Cache PUT: cacheName={}, key={}, shouldApplyTtl={}, finalTtl={}",
                  context.getCacheName(), context.getRedisKey(),
                  context.getOutput().isShouldApplyTtl(), context.getOutput().getFinalTtl());

        try {
            // 取消可能的异步预刷新任务
            preRefreshSupport.cancelAsyncRefresh(context.getRedisKey());

            // 获取存储值
            Object storeValue = context.getOutput().getStoreValue();
            if (storeValue == null) {
                storeValue = context.getDeserializedValue();
            }

            // 创建 CachedValue 并存储
            CachedValue cachedValue;
            if (context.getOutput().isShouldApplyTtl()) {
                long ttl = context.getOutput().getFinalTtl();
                cachedValue = CachedValue.of(storeValue, ttl);
                valueOperations.set(context.getRedisKey(), cachedValue, Duration.ofSeconds(ttl));
            } else {
                cachedValue = CachedValue.of(storeValue, -1);
                valueOperations.set(context.getRedisKey(), cachedValue);
            }

            statistics.incPuts(context.getCacheName());
            log.debug("Cache PUT success: cacheName={}, key={}", context.getCacheName(), context.getRedisKey());

            return CacheResult.success();

        } catch (Exception e) {
            return errorHandler.handlePutError(context.getCacheName(), context.getRedisKey(), e);
        }
    }

    // ==================== PUT_IF_ABSENT 操作 ====================

    /**
     * 处理 PUT_IF_ABSENT 操作
     */
    private CacheResult handlePutIfAbsent(CacheContext context) {
        Assert.hasText(context.getCacheName(), "Cache name must not be empty");
        Assert.hasText(context.getRedisKey(), "Redis key must not be empty");

        log.debug("Cache PUT_IF_ABSENT: cacheName={}, key={}", context.getCacheName(), context.getRedisKey());

        try {
            // 先检查是否存在
            CachedValue existingValue = (CachedValue) valueOperations.get(context.getRedisKey());
            if (isCacheHit(existingValue)) {
                log.debug("Cache PUT_IF_ABSENT: key exists, returning existing value: cacheName={}, key={}",
                          context.getCacheName(), context.getRedisKey());
                byte[] result = nullValuePolicy.toReturnValue(
                    existingValue.getValue(), context.getCacheName(), context.getRedisKey());
                return CacheResult.success(result);
            }

            // 获取存储值
            Object storeValue = context.getOutput().getStoreValue();
            if (storeValue == null) {
                storeValue = context.getDeserializedValue();
            }

            // 条件写入
            CachedValue cachedValue;
            Boolean success;

            if (context.getOutput().isShouldApplyTtl()) {
                long ttl = context.getOutput().getFinalTtl();
                cachedValue = CachedValue.of(storeValue, ttl);
                success = valueOperations.setIfAbsent(context.getRedisKey(), cachedValue, Duration.ofSeconds(ttl));
            } else {
                cachedValue = CachedValue.of(storeValue, -1);
                success = valueOperations.setIfAbsent(context.getRedisKey(), cachedValue);
            }

            if (Boolean.TRUE.equals(success)) {
                statistics.incPuts(context.getCacheName());
                log.debug("Cache PUT_IF_ABSENT success: cacheName={}, key={}", 
                          context.getCacheName(), context.getRedisKey());
                return CacheResult.success();
            }

            // 写入失败，返回当前值
            CachedValue actualValue = (CachedValue) valueOperations.get(context.getRedisKey());
            if (actualValue != null) {
                byte[] result = nullValuePolicy.toReturnValue(
                    actualValue.getValue(), context.getCacheName(), context.getRedisKey());
                return CacheResult.success(result);
            }

            return CacheResult.success();

        } catch (Exception e) {
            return errorHandler.handlePutIfAbsentError(context.getCacheName(), context.getRedisKey(), e);
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
            statistics.incDeletes(context.getCacheName());

            log.debug("Cache REMOVE completed: cacheName={}, key={}, deleted={}",
                      context.getCacheName(), context.getRedisKey(), deleted);

            return CacheResult.success();

        } catch (Exception e) {
            return errorHandler.handleRemoveError(context.getCacheName(), context.getRedisKey(), e);
        }
    }

    // ==================== CLEAN 操作 ====================

    /**
     * 处理 CLEAN 操作（批量清理）
     */
    private CacheResult handleClean(CacheContext context) {
        Assert.hasText(context.getCacheName(), "Cache name must not be empty");

        String keyPattern = context.getOutput().getKeyPattern();
        Assert.hasText(keyPattern, "Key pattern must not be empty");

        log.debug("Cache CLEAN: cacheName={}, pattern={}", context.getCacheName(), keyPattern);

        try {
            AtomicLong totalDeleted = new AtomicLong();

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
            if (deletedTotal > 0) {
                int reportedDeletes = deletedTotal > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) deletedTotal;
                statistics.incDeletesBy(context.getCacheName(), reportedDeletes);
            }

            log.debug("Cache CLEAN completed: cacheName={}, pattern={}, deletedCount={}",
                      context.getCacheName(), keyPattern, deletedTotal);

            return CacheResult.success();

        } catch (Exception e) {
            return errorHandler.handleCleanError(context.getCacheName(), keyPattern, e);
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
}
