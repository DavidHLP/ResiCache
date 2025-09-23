package com.david.spring.cache.redis.chain.handler;

import com.david.spring.cache.redis.cache.support.CacheOperationService;
import com.david.spring.cache.redis.chain.AbstractCacheHandler;
import com.david.spring.cache.redis.chain.CacheHandler;
import com.david.spring.cache.redis.chain.CacheHandlerContext;
import com.david.spring.cache.redis.chain.CacheOperationType;
import com.david.spring.cache.redis.config.CacheGuardProperties;
import com.david.spring.cache.redis.registry.RegistryFactory;
import com.david.spring.cache.redis.lock.DistributedLock;
import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 统一的缓存清除处理器。
 * <p>
 * 合并了EvictHandler和DelayedDeleteHandler的功能，提供：
 * - 单键删除 (EVICT)
 * - 全量清除 (CLEAR)
 * - 延迟双删机制
 * - 注册表清理
 * </p>
 *
 * @author CacheGuard
 * @since 2.0.0
 */
@Slf4j
public class CacheEvictHandler extends AbstractCacheHandler {

    private final CacheGuardProperties properties;

    public CacheEvictHandler(RegistryFactory registryFactory,
                           Executor executor,
                           CacheOperationService cacheOperationService,
                           CacheGuardProperties properties,
                           DistributedLock distributedLock) {
        super(registryFactory, executor, cacheOperationService, distributedLock);
        this.properties = properties;
    }

    @Override
    @Nonnull
    public String getName() {
        return "CacheEvict";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public boolean supports(@Nonnull CacheHandlerContext context) {
        CacheOperationType operationType = context.operationType();
        return operationType == CacheOperationType.EVICT ||
               operationType == CacheOperationType.CLEAR;
    }

    @Override
    protected CacheOperationType[] getSupportedOperations() {
        return new CacheOperationType[]{
                CacheOperationType.EVICT,
                CacheOperationType.CLEAR
        };
    }

    @Override
    @Nonnull
    protected HandlerResult doHandle(@Nonnull CacheHandlerContext context) {
        String operationName = context.operationType().name();

        logDebug("开始{}操作: cache={}, key={}", operationName,
                context.cacheName(), context.key());

        try {
            if (context.operationType() == CacheOperationType.EVICT) {
                handleEvict(context);
            } else if (context.operationType() == CacheOperationType.CLEAR) {
                handleClear(context);
            }

            logDebug("{}操作执行成功: cache={}, key={}", operationName,
                    context.cacheName(), context.key());

            return HandlerResult.HANDLED;

        } catch (Exception e) {
            logError("{}操作失败: cache={}, key={}, error={}", e, operationName,
                    context.cacheName(), context.key(), e.getMessage());
            return HandlerResult.CONTINUE;
        }
    }

    /**
     * 处理单键删除操作
     */
    private void handleEvict(CacheHandlerContext context) {
        // 立即执行删除
        context.callback().evictCache(context.cacheName(), context.key());

        // 清理注册表
        context.callback().cleanupRegistries(context.cacheName(), context.key());

        // 调度延迟删除
        scheduleDelayedDelete(context);
    }

    /**
     * 处理全量清除操作
     */
    private void handleClear(CacheHandlerContext context) {
        // 立即执行清除
        context.callback().clearCache(context.cacheName());

        // 清理注册表
        context.callback().cleanupAllRegistries(context.cacheName());

        // 调度延迟清除
        scheduleDelayedClear(context);
    }

    /**
     * 调度延迟删除任务
     */
    private void scheduleDelayedDelete(CacheHandlerContext context) {
        if (properties.getDoubleDeleteDelayMs() <= 0) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                executeWithLocks(context, () -> {
                    logDebug("执行延迟删除: cache={}, key={}",
                            context.cacheName(), context.key());

                    context.callback().evictCache(context.cacheName(), context.key());
                    context.callback().cleanupRegistries(context.cacheName(), context.key());

                    return null;
                });
            } catch (Exception e) {
                logError("延迟删除失败: cache={}, key={}, error={}", e,
                        context.cacheName(), context.key(), e.getMessage());
            }
        }, CompletableFuture.delayedExecutor(
                properties.getDoubleDeleteDelayMs(),
                TimeUnit.MILLISECONDS,
                executor));
    }

    /**
     * 调度延迟清除任务
     */
    private void scheduleDelayedClear(CacheHandlerContext context) {
        if (properties.getDoubleDeleteDelayMs() <= 0) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                executeWithLocks(context, () -> {
                    logDebug("执行延迟清除: cache={}", context.cacheName());

                    context.callback().clearCache(context.cacheName());
                    context.callback().cleanupAllRegistries(context.cacheName());

                    return null;
                });
            } catch (Exception e) {
                logError("延迟清除失败: cache={}, error={}", e,
                        context.cacheName(), e.getMessage());
            }
        }, CompletableFuture.delayedExecutor(
                properties.getDoubleDeleteDelayMs(),
                TimeUnit.MILLISECONDS,
                executor));
    }
}