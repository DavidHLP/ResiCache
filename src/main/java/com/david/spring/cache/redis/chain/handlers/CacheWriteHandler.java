package com.david.spring.cache.redis.chain.handlers;

import com.david.spring.cache.redis.chain.AbstractCacheHandler;
import com.david.spring.cache.redis.chain.CacheOperationHelper;
import com.david.spring.cache.redis.chain.context.CacheContext;
import com.david.spring.cache.redis.protection.CachePenetrationProtection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.stereotype.Component;

/**
 * 缓存写入处理器（优化版）
 * 在击穿防护后进行智能缓存写入：
 * - 检查是否需要更新缓存
 * - 检查unless条件和穿透情况
 * - 优化空值处理逻辑
 * - 为雪崩防护做准备
 *
 * @author david
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWriteHandler extends AbstractCacheHandler {

    private final CacheOperationHelper cacheOperationHelper;
    private final CachePenetrationProtection cachePenetrationProtection;

    @Override
    public String getName() {
        return "CacheWriteHandler";
    }

    @Override
    protected Object doHandle(CacheContext context) throws Throwable {
        // 1. 检查是否需要更新缓存
        if (!context.isNeedUpdateCache()) {
            log.debug("不需要更新缓存，跳过写入");
            return null;
        }

        // 2. 如果缓存已命中，不需要写入
        if (context.isCacheHit()) {
            log.debug("缓存已命中，不需要写入");
            return null;
        }

        // 3. 检查缓存条件
        if (!cacheOperationHelper.evaluateCondition(context.getRedisCacheable().condition(), context)) {
            log.debug("缓存条件不满足，跳过缓存写入");
            return null;
        }

        // 4. 检查unless条件
        if (!cacheOperationHelper.canPutToCache(context.getRedisCacheable().unless(), context)) {
            log.debug("unless条件不满足，跳过缓存写入");
            return null;
        }

        // 5. 获取缓存实例
        Cache cache = cacheOperationHelper.getCache(context.getCacheName());
        if (cache == null) {
            log.warn("未找到缓存，跳过写入: {}", context.getCacheName());
            return null;
        }

        // 6. 智能处理缓存写入
        String cacheKey = context.getCacheKey();
        Object valueToCache = context.getResult();

        if (valueToCache != null) {
            // 正常数据写入缓存
            cache.put(cacheKey, valueToCache);
            context.setNeedUpdateCache(false);
            log.debug("缓存写入成功: {}, 值: {}", cacheKey, valueToCache);

            // 同步更新布隆过滤器，记录这个键确实存在
            updateBloomFilter(cacheKey, context.getCacheName());
        } else {
            // 处理空值情况
            handleNullValue(context, cache, cacheKey);
        }

        return null;
    }

    /**
     * 更新布隆过滤器，记录缓存键确实存在
     */
    private void updateBloomFilter(String cacheKey, String cacheName) {
        try {
            // 使用缓存名称作为布隆过滤器名称，为每个缓存维护独立的过滤器
            String filterName = cacheName != null ? cacheName : "default";
            boolean added = cachePenetrationProtection.addToBloomFilter(cacheKey, filterName, null);

            if (added) {
                log.debug("布隆过滤器已更新: cache={}, key={}", cacheName, cacheKey);
            } else {
                log.warn("布隆过滤器更新失败: cache={}, key={}", cacheName, cacheKey);
            }
        } catch (Exception e) {
            log.warn("更新布隆过滤器异常: cache={}, key={}, error={}", cacheName, cacheKey, e.getMessage());
        }
    }

    /**
     * 处理空值情况
     */
    private void handleNullValue(CacheContext context, Cache cache, String cacheKey) {
        // 检查是否是可能的穿透请求
        if (context.isPossiblePenetration()) {
            log.debug("检测到可能的穿透请求，使用穿透防护缓存空值: {}", cacheKey);

            // 直接使用穿透防护缓存空值
            boolean cached = cachePenetrationProtection.cacheNullValue(context.getCacheName(), cacheKey, null);
            if (cached) {
                log.debug("空值防穿透缓存成功: {}", cacheKey);
            } else {
                log.warn("空值防穿透缓存失败: {}", cacheKey);
            }
            context.setNeedUpdateCache(false);
        } else {
            log.debug("结果为null且非穿透请求，跳过缓存写入: {}", cacheKey);
            context.setNeedUpdateCache(false);
        }
    }
}
