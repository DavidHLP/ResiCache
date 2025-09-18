package com.david.spring.cache.redis.strategy;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * 布隆过滤器策略
 * 使用布隆过滤器防止缓存穿透
 */
@Slf4j
@Component
public class BloomFilterStrategy extends AbstractCacheFetchStrategy {

    public BloomFilterStrategy(CacheInvocationRegistry registry, Executor executor,
                              CacheOperationService cacheOperationService) {
        super(registry, executor, cacheOperationService);
    }

    @Override
    public ValueWrapper fetch(CacheFetchContext context) {
        if (!context.invocationContext().useBloomFilter()) {
            return context.valueWrapper();
        }

        // 如果启用了布隆过滤器，先检查布隆过滤器
        if (context.valueWrapper() == null) {
            boolean mightExist = checkBloomFilter(context);
            if (!mightExist) {
                logDebug("Bloom filter indicates key does not exist: cache={}, key={}",
                        context.cacheName(), context.key());
                // 返回null，避免查询数据库
                return null;
            }
        } else {
            // 如果缓存存在，更新布隆过滤器
            updateBloomFilter(context);
        }

        return context.valueWrapper();
    }

    @Override
    public boolean supports(CachedInvocationContext invocationContext) {
        return invocationContext.useBloomFilter();
    }

    @Override
    public int getOrder() {
        return 5; // 在预刷新之前执行
    }

    private boolean checkBloomFilter(CacheFetchContext context) {
        // TODO: 实现布隆过滤器检查逻辑
        // 这里需要集成实际的布隆过滤器实现
        return true;
    }

    private void updateBloomFilter(CacheFetchContext context) {
        // TODO: 实现布隆过滤器更新逻辑
        // 将key添加到布隆过滤器中
    }
}