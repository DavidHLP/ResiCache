package com.david.spring.cache.redis.core.writer.chain.handler;

import com.david.spring.cache.redis.core.writer.chain.AbstractCacheHandler;
import com.david.spring.cache.redis.core.writer.chain.CacheOperation;
import com.david.spring.cache.redis.core.writer.support.TtlSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * TTL 处理器 职责：计算和配置缓存的 TTL - PUT/PUT_IF_ABSENT 操作：计算最终的 TTL（支持随机化） - 优先级：上下文配置的 TTL >
 * 方法参数传入的 TTL
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TtlHandler extends AbstractCacheHandler {

    private final TtlSupport ttlSupport;

    /** 默认 TTL（秒） */
    private static final long DEFAULT_TTL = 60;

    @Override
    protected boolean shouldHandle(CacheContext context) {
        // 只处理 PUT 和 PUT_IF_ABSENT 操作
        return context.getOperation() == CacheOperation.PUT
                || context.getOperation() == CacheOperation.PUT_IF_ABSENT;
    }

    @Override
    protected CacheResult doHandle(CacheContext context) {
        // 计算 TTL
        calculateTtl(context);

        // 继续责任链
        return invokeNext(context);
    }

    /** 计算 TTL 并设置到上下文中 */
    private void calculateTtl(CacheContext context) {
        Duration ttl = context.getTtl();
        if (ttl == null) {
            ttl = Duration.ofSeconds(DEFAULT_TTL);
        }

        // 优先使用上下文配置的 TTL
        if (context.getCacheOperation() != null
                && context.getCacheOperation().getTtl() > 0) {
            long finalTtl =
                    ttlSupport.calculateFinalTtl(
                            context.getCacheOperation().getTtl(),
                            context.getCacheOperation().isRandomTtl(),
                            context.getCacheOperation().getVariance());

            context.setFinalTtl(finalTtl);
            context.setShouldApplyTtl(true);
            context.setTtlFromContext(true);

            log.debug(
                    "Using context TTL configuration: cacheName={}, key={}, baseTtl={}s, finalTtl={}s, randomTtl={}, variance={}",
                    context.getCacheName(),
                    context.getRedisKey(),
                    context.getCacheOperation().getTtl(),
                    finalTtl,
                    context.getCacheOperation().isRandomTtl(),
                    context.getCacheOperation().getVariance());
        } else if (ttlSupport.shouldApplyTtl(ttl)) {
            // 使用方法参数传入的 TTL
            long finalTtl = ttl.getSeconds();
            context.setFinalTtl(finalTtl);
            context.setShouldApplyTtl(true);
            context.setTtlFromContext(false);

            log.debug(
                    "Using parameter TTL: cacheName={}, key={}, ttl={}s",
                    context.getCacheName(),
                    context.getRedisKey(),
                    finalTtl);
        } else {
            // 不应用 TTL
            context.setFinalTtl(-1);
            context.setShouldApplyTtl(false);
            context.setTtlFromContext(false);

            log.debug(
                    "No TTL applied: cacheName={}, key={}",
                    context.getCacheName(),
                    context.getRedisKey());
        }
    }
}
