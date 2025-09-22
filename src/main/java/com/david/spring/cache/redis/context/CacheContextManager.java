package com.david.spring.cache.redis.context;

import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.factory.RegistryFactory;
import com.david.spring.cache.redis.strategy.CacheFetchStrategy;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 统一的缓存上下文管理器
 * 负责管理、验证和创建各种缓存操作上下文
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheContextManager {

    private final RegistryFactory registryFactory;

    /**
     * 获取缓存调用上下文
     *
     * @param cacheName 缓存名称
     * @param key       缓存键
     * @return 缓存调用上下文，可能为null
     */
    @Nullable
    public CachedInvocationContext getInvocationContext(@Nonnull String cacheName, @Nonnull Object key) {
        try {
            Optional<CachedInvocation> invocationOpt = registryFactory.getCacheInvocationRegistry().get(cacheName, key);
            return invocationOpt.map(CachedInvocation::getCachedInvocationContext).orElse(null);
        } catch (Exception e) {
            log.warn("Failed to get invocation context for cache: {}, key: {}, error: {}",
                    cacheName, key, e.getMessage());
            return null;
        }
    }

    /**
     * 获取缓存调用信息
     *
     * @param cacheName 缓存名称
     * @param key       缓存键
     * @return 缓存调用信息
     */
    @Nullable
    public CachedInvocation getCachedInvocation(@Nonnull String cacheName, @Nonnull Object key) {
        try {
            return registryFactory.getCacheInvocationRegistry().get(cacheName, key).orElse(null);
        } catch (Exception e) {
            log.warn("Failed to get cached invocation for cache: {}, key: {}, error: {}",
                    cacheName, key, e.getMessage());
            return null;
        }
    }

    /**
     * 验证调用上下文是否有效
     *
     * @param context 调用上下文
     * @return true表示有效
     */
    public boolean isValidInvocationContext(@Nullable CachedInvocationContext context) {
        if (context == null) {
            return false;
        }

        // 基本验证
        if (context.value() == null && context.cacheNames() == null) {
            log.debug("Invalid context: missing cache names");
            return false;
        }

        // 检查关键配置
        if (context.keyGenerator() == null || context.keyGenerator().trim().isEmpty()) {
            log.debug("Invalid context: missing key generator");
            return false;
        }

        return true;
    }

    /**
     * 判断是否应该执行策略
     *
     * @param context      调用上下文
     * @param valueWrapper 当前缓存值
     * @return true表示应该执行策略
     */
    public boolean shouldExecuteStrategies(@Nullable CachedInvocationContext context, @Nullable ValueWrapper valueWrapper) {
        if (context == null) {
            return false;
        }

        // 如果没有配置特殊策略，且已有缓存值，则不需要执行策略
        if (!hasCustomStrategy(context) && valueWrapper != null && valueWrapper.get() != null) {
            return false;
        }

        // 如果配置了预刷新等策略，则需要执行
        return hasCustomStrategy(context);
    }

    /**
     * 创建策略执行上下文
     *
     * @param cacheName     缓存名称
     * @param key           缓存键
     * @param cacheKey      Redis缓存键
     * @param valueWrapper  当前缓存值
     * @param invocation    缓存调用信息
     * @param redisTemplate Redis模板
     * @param callback      回调接口
     * @return 策略执行上下文
     */
    @Nullable
    public CacheFetchStrategy.CacheFetchContext createFetchContext(
            @Nonnull String cacheName,
            @Nonnull Object key,
            @Nonnull String cacheKey,
            @Nullable ValueWrapper valueWrapper,
            @Nonnull CachedInvocation invocation,
            @Nonnull RedisTemplate<String, Object> redisTemplate,
            @Nonnull CacheFetchStrategy.CacheFetchCallback callback) {

        CachedInvocationContext invocationContext = invocation.getCachedInvocationContext();
        if (!isValidInvocationContext(invocationContext)) {
            return null;
        }

        return new CacheFetchStrategy.CacheFetchContext(
                cacheName, key, cacheKey, valueWrapper, invocation,
                invocationContext, redisTemplate, callback);
    }

    /**
     * 验证策略执行上下文是否有效
     *
     * @param context 策略执行上下文
     * @return true表示有效
     */
    public boolean isValidFetchContext(@Nullable CacheFetchStrategy.CacheFetchContext context) {
        if (context == null) {
            return false;
        }

        if (context.cacheName() == null || context.cacheName().trim().isEmpty()) {
            return false;
        }

        if (context.key() == null || context.cacheKey() == null) {
            return false;
        }

        if (context.invocation() == null || context.redisTemplate() == null || context.callback() == null) {
            return false;
        }

        return isValidInvocationContext(context.invocationContext());
    }

    /**
     * 检查是否配置了自定义策略
     */
    private boolean hasCustomStrategy(@Nonnull CachedInvocationContext context) {
        return context.enablePreRefresh() ||
               context.useBloomFilter() ||
               context.fetchStrategy() != CachedInvocationContext.FetchStrategyType.AUTO ||
               (context.customStrategyClass() != null && !context.customStrategyClass().trim().isEmpty());
    }
}