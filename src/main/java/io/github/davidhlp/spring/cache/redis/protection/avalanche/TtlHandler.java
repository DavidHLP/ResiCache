package io.github.davidhlp.spring.cache.redis.protection.avalanche;

import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.model.*;


import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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

    private final DefaultTtlPolicy ttlPolicy;

    /**
     * guide §223b1:缺失的 TtlHandler counter —— TTL jitter 应用事件(防雪崩:randomTtl=true
     * 的 variance 展开计数)。{@link ObjectProvider} 允许 MeterRegistry 缺失,counter 为 null 时 no-op。
     */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private Counter ttlJitteredCounter;

    private static final long DEFAULT_TTL = 60;

    @PostConstruct
    void initMetrics() {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            this.ttlJitteredCounter = Counter.builder("resicache.handler.ttl.jittered")
                    .description("TTL jitter applied (avalanche protection: randomTtl=true variance spread the TTL)")
                    .register(registry);
        }
    }

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

            // guide §223b1:TTL jitter 应用计数(randomTtl=true 时 variance 展开)
            if (context.getCacheOperation().isRandomTtl() && ttlJitteredCounter != null) {
                ttlJitteredCounter.increment();
            }

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
