package com.david.spring.cache.redis.strategy.cacheable.support;

import com.david.spring.cache.redis.strategy.cacheable.context.CacheableContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

/**
 * 缓存保护管理器
 * 统一管理缓存穿透、击穿、雪崩等保护机制
 *
 * @author David
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheProtectionManager {

    private final PenetrationProtector penetrationProtector;
    private final BreakdownProtector breakdownProtector;
    private final AvalancheProtector avalancheProtector;
    private final CacheOperationSupport cacheOperationSupport;

    /**
     * 受保护的缓存获取
     *
     * @param context 缓存获取上下文
     * @return 缓存值包装器
     */
    @Nullable
    public Cache.ValueWrapper getWithProtection(@NonNull CacheableContext<Object> context) {
        // 1. 缓存穿透保护
        if (!penetrationProtector.isAllowed(context)) {
            log.debug("Cache penetration protection blocked access for key: {}", context.getKey());
            return null;
        }

        // 2. 尝试获取缓存
        Cache.ValueWrapper valueWrapper = cacheOperationSupport.safeGet(context);

        if (valueWrapper == null) {
            log.debug("Cache miss with protection for key: {}", context.getKey());
            // 3. 缓存击穿保护
            return breakdownProtector.handleBreakdown(context);
        } else {
            log.debug("Cache hit with protection for key: {}", context.getKey());
            // 4. 缓存雪崩保护 (命中时)
            avalancheProtector.onCacheHit(context);
        }

        return valueWrapper;
    }

    /**
     * 受保护的缓存获取（带值加载器）
     *
     * @param context     缓存获取上下文
     * @param valueLoader 值加载器
     * @return 缓存值或加载的值
     */
    @Nullable
    public <V> V getWithProtection(@NonNull CacheableContext<Object> context, @NonNull Callable<V> valueLoader) {
        // 1. 缓存穿透保护
        if (!penetrationProtector.isAllowed(context)) {
            log.debug("Cache penetration protection blocked access for key: {}", context.getKey());
            return null;
        }

        // 2. 尝试从缓存获取
        Cache.ValueWrapper valueWrapper = cacheOperationSupport.safeGet(context);
        if (valueWrapper != null) {
            log.debug("Cache hit with protection for key: {}", context.getKey());
            avalancheProtector.onCacheHit(context);
            @SuppressWarnings("unchecked")
            V value = (V) valueWrapper.get();
            return value;
        }

        // 3. 缓存未命中，使用击穿保护加载值
        log.debug("Cache miss with protection for key: {}. Applying breakdown protection.", context.getKey());
        return breakdownProtector.loadWithBreakdownProtection(context, valueLoader, this::loadAndCacheWithProtection);
    }

    /**
     * 带保护的值加载和缓存
     */
    @Nullable
    private <V> V loadAndCacheWithProtection(CacheableContext<Object> context, Callable<V> valueLoader) {
        V value = cacheOperationSupport.loadAndCache(context, valueLoader);
        // 雪崩保护 - 记录缓存写入
        avalancheProtector.onCachePut(context);
        return value;
    }
}