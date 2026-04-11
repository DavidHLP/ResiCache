package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.ttl.TtlPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * TTL 处理器
 * 
 * 职责：
 * 1. 计算最终的 TTL 值
 * 2. 支持从配置或参数获取 TTL
 * 3. 支持随机化 TTL（防止缓存雪崩）
 * 
 * 输出（设置到 CacheOutput）：
 * - shouldApplyTtl: 是否应用 TTL
 * - finalTtl: 最终 TTL（秒）
 * - ttlFromContext: TTL 是否来自配置
 */
@Slf4j
@Component
@RequiredArgsConstructor
@HandlerPriority(HandlerOrder.TTL)
public class TtlHandler extends AbstractCacheHandler {

    private final TtlPolicy ttlPolicy;

    private static final long DEFAULT_TTL = 60;

    @Override
    protected boolean shouldHandle(CacheContext context) {
        return context.getOperation() == CacheOperation.PUT
                || context.getOperation() == CacheOperation.PUT_IF_ABSENT;
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        calculateTtl(context);
        // 继续执行后续 Handler
        return HandlerResult.continueChain();
    }

    /**
     * 计算 TTL
     */
    private void calculateTtl(CacheContext context) {
        Duration ttl = context.getTtl();
        if (ttl == null) {
            ttl = Duration.ofSeconds(DEFAULT_TTL);
        }

        // 优先使用配置中的 TTL
        if (context.getCacheOperation() != null
                && context.getCacheOperation().getTtl() > 0) {
            long finalTtl =
                    ttlPolicy.calculateFinalTtl(
                            context.getCacheOperation().getTtl(),
                            context.getCacheOperation().isRandomTtl(),
                            context.getCacheOperation().getVariance());

            context.getOutput().setFinalTtl(finalTtl);
            context.getOutput().setShouldApplyTtl(true);
            context.getOutput().setTtlFromContext(true);

            log.debug(
                    "Using context TTL configuration: cacheName={}, key={}, baseTtl={}s, finalTtl={}s, randomTtl={}, variance={}",
                    context.getCacheName(),
                    context.getRedisKey(),
                    context.getCacheOperation().getTtl(),
                    finalTtl,
                    context.getCacheOperation().isRandomTtl(),
                    context.getCacheOperation().getVariance());
        } else if (ttlPolicy.shouldApply(ttl)) {
            // 使用参数中的 TTL
            long finalTtl = ttl.getSeconds();
            context.getOutput().setFinalTtl(finalTtl);
            context.getOutput().setShouldApplyTtl(true);
            context.getOutput().setTtlFromContext(false);

            log.debug(
                    "Using parameter TTL: cacheName={}, key={}, ttl={}s",
                    context.getCacheName(),
                    context.getRedisKey(),
                    finalTtl);
        } else {
            // 不应用 TTL（永久缓存）
            context.getOutput().setFinalTtl(-1);
            context.getOutput().setShouldApplyTtl(false);
            context.getOutput().setTtlFromContext(false);

            log.debug(
                    "No TTL applied: cacheName={}, key={}",
                    context.getCacheName(),
                    context.getRedisKey());
        }
    }
}
