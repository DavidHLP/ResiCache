package com.david.spring.cache.redis.chain.handlers;

import com.david.spring.cache.redis.chain.AbstractCacheHandler;
import com.david.spring.cache.redis.chain.CacheOperationHelper;
import com.david.spring.cache.redis.chain.context.CacheContext;
import com.david.spring.cache.redis.protection.CacheBreakdownProtection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 缓存击穿保护和方法执行处理器（优化版）
 * 在穿透防护后进行击穿防护和方法执行：
 * - 缓存未命中时执行原始方法
 * - sync=true时使用分布式锁防止击穿
 * - 智能判断是否需要击穿防护
 * - 支持降级策略和异步更新
 * - 统一方法执行逻辑
 *
 * @author david
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheBreakdownHandler extends AbstractCacheHandler {

    private final CacheBreakdownProtection cacheBreakdownProtection;
    private final CacheOperationHelper cacheOperationHelper;

    @Override
    public String getName() {
        return "CacheBreakdownHandler";
    }

    @Override
    protected Object doHandle(CacheContext context) throws Throwable {
        // 如果缓存命中，不需要方法执行
        if (context.isCacheHit()) {
            log.debug("缓存命中，跳过方法执行");
            return null;
        }

        // 智能判断是否需要击穿防护
        boolean needBreakdownProtection = shouldUseBreakdownProtection(context);

        if (needBreakdownProtection) {
            log.debug("使用击穿防护执行方法: sync={}, possibleHighConcurrency=true", context.getRedisCacheable().sync());
            return performBreakdownProtection(context);
        } else {
            log.debug("直接执行方法（低并发场景）");
            cacheOperationHelper.executeMethod(context);
            return null; // 继续下一个处理器
        }
    }

    /**
     * 智能判断是否需要击穿防护
     */
    private boolean shouldUseBreakdownProtection(CacheContext context) {
        // 1. 强制同步模式
        if (context.getRedisCacheable().sync()) {
            return true;
        }

        // 2. 可能的穿透请求（布隆过滤器判定不存在）- 但仍可能是正常的新数据
        if (context.isPossiblePenetration()) {
            log.debug("布隆过滤器提示可能的穿透，使用轻量级防护");
            return true;
        }

        // 3. 热点数据键（可以根据键名规则判断）
        String cacheKey = context.getCacheKey();
        if (isHotKey(cacheKey)) {
            log.debug("检测到热点数据键，启用击穿防护");
            return true;
        }

        // 4. 默认不使用击穿防护（提高性能）
        return false;
    }

    /**
     * 判断是否为热点数据键
     */
    private boolean isHotKey(String cacheKey) {
        // 可以根据业务规则定制，例如：
        // - 用户信息、热门商品等
        // - 数据库中的热点统计
        // - Redis中的访问计数

        return cacheKey.contains("user:") ||
                cacheKey.contains("product:") ||
                cacheKey.contains("hot:");
    }

    /**
     * 执行击穿防护
     */
    private Object performBreakdownProtection(CacheContext context) throws Throwable {
        String cacheName = context.getCacheName();
        String cacheKey = context.getCacheKey();

        log.debug("开始缓存击穿防护，缓存: {}, 键: {}", cacheName, cacheKey);

        try {
            // 使用CacheBreakdownProtection进行防护处理
            CacheBreakdownProtection.ProtectionResult<Object> result = cacheBreakdownProtection
                    .executeWithProtection(cacheName, cacheKey, () -> {
                        // 使用统一的方法执行逻辑
                        try {
                            return cacheOperationHelper.executeMethod(context);
                        } catch (Throwable e) {
                            log.error("缓存击穿防护中执行方法失败", e);
                            context.setException(e);
                            throw new RuntimeException("Method execution failed during breakdown protection", e);
                        }
                    });

            if (result.cacheHit()) {
                // 缓存命中（双重检查命中），更新上下文
                context.setCacheValue(result.value());
                context.setCacheHit(true);
                context.setNeedUpdateCache(false);

                log.debug("击穿防护双重检查命中: {}, 执行时间: {}ms",
                        cacheKey, result.executionTimeMs());

                context.setProcessed(true);
                return result.value();
            } else {
                // 缓存未命中，方法已执行
                context.setResult(result.value());

                // 标记需要更新缓存（让后续处理器处理）
                context.setNeedUpdateCache(true);

                log.debug("击穿防护方法执行完成: {}, 获得锁: {}, 执行时间: {}ms",
                        cacheKey, result.lockAcquired(), result.executionTimeMs());
            }
        } catch (Exception e) {
            log.error("执行缓存击穿防护失败，键: {}", cacheKey, e);

            // 防护失败，降级使用直接执行
            if (context.getResult() == null) {
                log.warn("击穿防护失败，降级直接执行方法");
                cacheOperationHelper.executeMethod(context);
            }
        }

        return null; // 继续下一个处理器
    }
}
