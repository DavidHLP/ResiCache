package com.david.spring.cache.redis.chain.handlers;

import com.david.spring.cache.redis.chain.AbstractCacheHandler;
import com.david.spring.cache.redis.chain.CacheOperationHelper;
import com.david.spring.cache.redis.chain.context.CacheContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.stereotype.Component;

/**
 * 缓存读取处理器
 * 基于Spring CacheAspectSupport的缓存读取逻辑
 *
 * @author david
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheReadHandler extends AbstractCacheHandler {

    private final CacheOperationHelper cacheOperationHelper;

    @Override
    public String getName() {
        return "CacheReadHandler";
    }

    @Override
    protected Object doHandle(CacheContext context) throws Throwable {
        // 检查缓存条件
        if (!cacheOperationHelper.evaluateCondition(context.getRedisCacheable().condition(), context)) {
            log.debug("缓存条件不满足，跳过缓存读取");
            context.setProcessed(true); // 终止责任链执行
            return context.getResult(); // 返回当前结果（通常为null）
        }

        // 获取缓存
        Cache cache = cacheOperationHelper.getCache(context.getCacheName());
        if (cache == null) {
            return null;
        }

        // 从缓存中读取值
        String cacheKey = context.getCacheKey();
        Cache.ValueWrapper valueWrapper = cache.get(cacheKey);

        if (valueWrapper != null) {
            Object cachedValue = valueWrapper.get();
            context.setCacheValue(cachedValue);
            context.setCacheHit(true);
            context.setNeedUpdateCache(false);
            context.setProcessed(true);

            log.debug("缓存命中，键: {}, 值: {}", cacheKey, cachedValue);
            return cachedValue;
        }

        log.debug("缓存未命中，键: {}", cacheKey);
        return null;
    }

}
