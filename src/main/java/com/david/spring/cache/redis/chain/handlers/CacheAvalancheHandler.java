package com.david.spring.cache.redis.chain.handlers;

import com.david.spring.cache.redis.chain.AbstractCacheHandler;
import com.david.spring.cache.redis.chain.CacheOperationHelper;
import com.david.spring.cache.redis.chain.context.CacheContext;
import com.david.spring.cache.redis.protection.CacheAvalancheProtection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 缓存雪崩保护处理器（优化版）
 * 作为责任链的最后一个处理器，提供全面的雪崩防护功能：
 * - 智能判断是否需要雪崩防护写入
 * - 随机TTL防止集体失效
 * - 健康检查和熔断器
 * - 处理穿透防护的空值缓存
 * - 统一返回最终结果
 *
 * @author david
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheAvalancheHandler extends AbstractCacheHandler {

    private final CacheAvalancheProtection cacheAvalancheProtection;
    private final CacheOperationHelper cacheOperationHelper;

    @Override
    public String getName() {
        return "CacheAvalancheHandler";
    }

    @Override
    protected Object doHandle(CacheContext context) throws Throwable {
        // 智能判断是否需要雪崩防护
        if (shouldUseAvalancheProtection(context)) {
            performAvalancheProtection(context);
        }

        // 作为责任链的最后一个处理器，标记处理完成并返回最终结果
        context.setProcessed(true);
        Object finalResult = cacheOperationHelper.getReturnValue(context);
        
        log.debug("责任链执行完成，最终结果: {}, 缓存命中: {}",
                finalResult != null ? "有数据" : "null", context.isCacheHit());
        
        return finalResult;
    }

    /**
     * 智能判断是否需要雪崩防护
     */
    private boolean shouldUseAvalancheProtection(CacheContext context) {
        // 1. 缓存已命中，不需要雪崩防护
        if (context.isCacheHit()) {
            return false;
        }
        
        // 2. 不需要更新缓存
        if (!context.isNeedUpdateCache()) {
            return false;
        }
        
        // 3. 有正常数据需要缓存
        if (context.getResult() != null) {
            return true;
        }
        
        // 4. 空值但是可能的穿透请求，需要防护缓存
        if (context.isPossiblePenetration()) {
            log.debug("空值穿透请求需要雪崩防护缓存");
            return true;
        }
        
        // 5. 其他情况不需要防护
        return false;
    }

    /**
     * 执行雪崩防护
     */
    private void performAvalancheProtection(CacheContext context) {
        try {
            String cacheName = context.getCacheName();
            String cacheKey = context.getCacheKey();
            Object valueToCache = context.getResult();
            long baseTtl = context.getRedisCacheable().ttl();

            // 处理空值穿透防护缓存
            if (valueToCache == null && context.isPossiblePenetration()) {
                log.debug("执行空值穿透防护缓存: {}", cacheKey);
                handlePenetrationNullCache(context, cacheName, cacheKey);
                return;
            }

            // 处理正常数据的雪崩防护
            if (valueToCache != null) {
                log.debug("开始缓存雪崩防护: {}, 基础TTL: {}秒", cacheKey, baseTtl);

                // 先写入缓存值
                cacheOperationHelper.putToCache(context, valueToCache);
                
                // 然后使用CacheAvalancheProtection设置随机TTL防护
                CacheAvalancheProtection.ProtectionResult<Void> result = cacheAvalancheProtection
                        .setTtlWithProtection(cacheName, cacheKey, baseTtl);

                if (result.isCacheWritten()) {
                    log.debug("雪崩防护TTL设置成功: {}, 实际TTL: {}秒, 状态: {}",
                            cacheKey,
                            result.getActualTtl() != null ? result.getActualTtl().getSeconds() : "默认",
                            result.getStatus());

                    // 标记已处理，避免重复写入
                    context.setNeedUpdateCache(false);
                } else {
                    if (result.isFallbackUsed()) {
                        log.warn("雪崩防护TTL设置使用降级策略: {}, 状态: {}", cacheKey, result.getStatus());
                    } else {
                        log.warn("雪崩防护TTL设置失败: {}, 状态: {}", cacheKey, result.getStatus());
                    }
                }
            }

        } catch (Exception e) {
            log.error("执行缓存雪崩防护失败: {}", context.getCacheKey(), e);

            // 记录错误到防护系统
            try {
                cacheAvalancheProtection.recordCacheError(e);
            } catch (Exception recordError) {
                log.warn("记录雪崩防护错误失败", recordError);
            }
        }
    }

    /**
     * 处理穿透防护的空值缓存
     */
    private void handlePenetrationNullCache(CacheContext context, String cacheName, String cacheKey) {
        try {
            // 先写入null值防止穿透
            cacheOperationHelper.putToCache(context, null);
            
            // 使用短TTL设置过期时间，防止穿透攻击，自带随机TTL
            long nullValueTtl = CacheAvalancheProtection.MIN_TTL_SECONDS * 5; // 5分钟
            CacheAvalancheProtection.ProtectionResult<Void> result = cacheAvalancheProtection
                    .setTtlWithProtection(cacheName, cacheKey, nullValueTtl);

            if (result.isCacheWritten()) {
                log.debug("穿透防护空值TTL设置成功: {}, TTL: {}秒",
                        cacheKey, result.getActualTtl() != null ? result.getActualTtl().getSeconds() : "默认");
            } else {
                log.warn("穿透防护空值TTL设置失败: {}, 状态: {}", cacheKey, result.getStatus());
            }

            context.setNeedUpdateCache(false);
        } catch (Exception e) {
            log.error("穿透防护空值缓存异常: {}", cacheKey, e);
        }
    }
}
