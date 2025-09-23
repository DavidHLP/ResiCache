package com.david.spring.cache.redis.chain.handler;

import com.david.spring.cache.redis.chain.AbstractCacheHandler;
import com.david.spring.cache.redis.chain.CacheHandlerContext;
import com.david.spring.cache.redis.chain.CacheOperationType;
import com.david.spring.cache.redis.cache.support.CacheOperationService;
import com.david.spring.cache.redis.chain.protection.CachePenetration;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.RegistryFactory;
import com.david.spring.cache.redis.lock.DistributedLock;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * 布隆过滤器处理器。
 * <p>
 * 在职责链中负责布隆过滤器检查，防止缓存穿透攻击。
 * 在缓存未命中时，先检查布隆过滤器以避免无效的数据库查询。
 * </p>
 *
 * @author CacheGuard
 * @since 2.0.0
 */
@Slf4j
@Component
public class BloomFilterHandler extends AbstractCacheHandler {

    /** 布隆过滤器服务，用于判断键是否可能存在 */
    private final CachePenetration cachePenetration;

    public BloomFilterHandler(RegistryFactory registryFactory,
                             @Qualifier("cacheRefreshExecutor") Executor executor,
                             CacheOperationService cacheOperationService,
                             CachePenetration cachePenetration,
                             DistributedLock distributedLock) {
        super(registryFactory, executor, cacheOperationService, distributedLock);
        this.cachePenetration = cachePenetration;
    }

    @Override
    @Nullable
    protected CacheOperationType[] getSupportedOperations() {
        // 布隆过滤器主要用于读取操作的穿透保护
        return new CacheOperationType[]{
                CacheOperationType.READ
        };
    }

    @Override
    @Nonnull
    protected HandlerResult doHandle(@Nonnull CacheHandlerContext context) {
        logDebug("Processing bloom filter check for cache={}, key={}", context.cacheName(), context.key());

        return executeWithMonitoring("bloom-filter-check", context, () -> {
            // 检查是否启用布隆过滤器
            if (!context.invocationContext().useBloomFilter()) {
                logDebug("Bloom filter disabled, proceeding to next handler");
                return proceedToNext(context);
            }

            // 如果已经有缓存值，更新布隆过滤器并继续
            if (context.hasValue()) {
                return handleCacheHit(context);
            }

            // 缓存未命中，执行布隆过滤器检查
            return handleCacheMiss(context);
        });
    }

    /**
     * 处理缓存命中场景。
     * <p>
     * 更新布隆过滤器并继续执行链。
     * </p>
     */
    @Nonnull
    private HandlerResult handleCacheHit(@Nonnull CacheHandlerContext context) {
        Object value = context.getValue();
        boolean shouldUpdate = value != null || context.invocationContext().cacheNullValues();

        if (shouldUpdate) {
            updateBloomFilter(context);
        }

        logDebug("Cache hit processed, proceeding to next handler");
        return proceedToNext(context);
    }

    /**
     * 处理缓存未命中场景。
     * <p>
     * 检查布隆过滤器，如果键不存在则阻止继续执行。
     * </p>
     */
    @Nonnull
    private HandlerResult handleCacheMiss(@Nonnull CacheHandlerContext context) {
        boolean mightExist = checkBloomFilter(context);

        if (!mightExist) {
            logDebug("Bloom filter blocked non-existent key: cache={}, key={}",
                    context.cacheName(), context.key());

            // 即使配置了cacheNullValues，布隆过滤器也应该阻止访问
            if (context.invocationContext().cacheNullValues()) {
                logDebug("Bloom filter blocking despite cacheNullValues=true: cache={}, key={}",
                        context.cacheName(), context.key());
            }

            return HandlerResult.BLOCKED;
        }

        logDebug("Bloom filter passed for key: cache={}, key={}", context.cacheName(), context.key());
        return proceedToNext(context);
    }

    @Override
    public boolean supports(@Nonnull CacheHandlerContext context) {
        CachedInvocationContext invocationContext = context.invocationContext();
        return invocationContext.useBloomFilter();
    }

    @Override
    public int getOrder() {
        return 5; // 高优先级，在大部分处理器之前执行
    }

    @Override
    @Nonnull
    public String getName() {
        return "BloomFilter";
    }

    /**
     * 检查布隆过滤器。
     *
     * @param context 处理上下文
     * @return true表示键可能存在
     */
    private boolean checkBloomFilter(CacheHandlerContext context) {
        String membershipKey = String.valueOf(context.key());
        boolean mightExist = cachePenetration.mightContain(context.cacheName(), membershipKey);
        logDebug("Bloom filter check: cache={}, key={}, mightExist={}",
                context.cacheName(), membershipKey, mightExist);
        return mightExist;
    }

    /**
     * 更新布隆过滤器。
     *
     * @param context 处理上下文
     */
    private void updateBloomFilter(CacheHandlerContext context) {
        try {
            String membershipKey = String.valueOf(context.key());
            cachePenetration.addIfEnabled(context.cacheName(), membershipKey);
            logDebug("Added key to bloom filter: cache={}, key={}",
                    context.cacheName(), membershipKey);
        } catch (Exception e) {
            logWarn("Failed to update bloom filter: cache={}, key={}, error={}",
                    context.cacheName(), context.key(), e.getMessage());
        }
    }
}