package com.david.spring.cache.redis.chain.handler;

import com.david.spring.cache.redis.cache.support.CacheOperationService;
import com.david.spring.cache.redis.chain.AbstractCacheHandler;
import com.david.spring.cache.redis.chain.CacheHandlerContext;
import com.david.spring.cache.redis.chain.CacheOperationType;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.RegistryFactory;
import com.david.spring.cache.redis.lock.DistributedLock;
import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 预刷新处理器。
 * <p>
 * 检测缓存是否需要预刷新，当缓存即将过期时异步触发缓存刷新。
 * </p>
 *
 * @author CacheGuard
 * @since 2.0.0
 */
@Slf4j
public class PreRefreshHandler extends AbstractCacheHandler {

    public PreRefreshHandler(RegistryFactory registryFactory,
                           Executor executor,
                           CacheOperationService cacheOperationService,
                           DistributedLock distributedLock) {
        super(registryFactory, executor, cacheOperationService, distributedLock);
    }

    @Override
    @Nonnull
    public String getName() {
        return "PreRefresh";
    }

    @Override
    public int getOrder() {
        return 30;
    }

    @Override
    public boolean supports(@Nonnull CacheHandlerContext context) {
        CachedInvocationContext invocationContext = context.invocationContext();
        return invocationContext.enablePreRefresh() || isPreRefreshCandidate(invocationContext);
    }

    @Override
    protected CacheOperationType[] getSupportedOperations() {
        return new CacheOperationType[]{
                CacheOperationType.READ
        };
    }

    @Override
    @Nonnull
    protected HandlerResult doHandle(@Nonnull CacheHandlerContext context) {
        if (context.operationType() != CacheOperationType.READ || !context.hasValue()) {
            return HandlerResult.CONTINUE;
        }

        logDebug("检查预刷新需求: cache={}, key={}", context.cacheName(), context.key());

        try {
            if (shouldTriggerPreRefresh(context)) {
                triggerAsyncPreRefresh(context);
            }
        } catch (Exception e) {
            logError("预刷新检查失败: cache={}, key={}, error={}", e,
                    context.cacheName(), context.key(), e.getMessage());
        }

        return HandlerResult.CONTINUE;
    }

    /**
     * 判断是否为预刷新候选
     */
    private boolean isPreRefreshCandidate(CachedInvocationContext context) {
        return context.ttl() > 0 &&
               context.getEffectivePreRefreshThreshold() > 0 &&
               (context.distributedLock() || context.internalLock());
    }

    /**
     * 判断是否应该触发预刷新
     */
    private boolean shouldTriggerPreRefresh(CacheHandlerContext context) {
        long configuredTtl = context.callback().resolveConfiguredTtlSeconds(
                context.getValue(), context.key());

        if (configuredTtl <= 0) {
            return false;
        }

        // 获取当前TTL并检查是否需要预刷新
        return context.callback().shouldPreRefresh(-1, configuredTtl);
    }

    /**
     * 异步触发预刷新
     */
    private void triggerAsyncPreRefresh(CacheHandlerContext context) {
        logDebug("触发异步预刷新: cache={}, key={}", context.cacheName(), context.key());

        CompletableFuture.runAsync(() -> {
            executeWithLocks(context, () -> {
                // 再次检查是否仍需要刷新
                if (shouldTriggerPreRefresh(context)) {
                    long ttl = context.callback().resolveConfiguredTtlSeconds(
                            context.getValue(), context.key());

                    context.callback().refresh(
                            context.invocation(),
                            context.key(),
                            context.cacheKey(),
                            ttl
                    );

                    logDebug("预刷新执行完成: cache={}, key={}",
                            context.cacheName(), context.key());
                }
                return null;
            });
        }, executor).exceptionally(throwable -> {
            logError("异步预刷新失败: cache={}, key={}, error={}", throwable,
                    context.cacheName(), context.key(), throwable.getMessage());
            return null;
        });
    }
}