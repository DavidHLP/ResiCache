package io.github.davidhlp.spring.cache.redis.protection.avalanche;

import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.handler.ActualCacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.model.*;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * TTL 处理器
 *
 * <p>职责：
 * <ol>
 *   <li>计算最终的 TTL 值</li>
 *   <li>支持从配置或参数获取 TTL</li>
 *   <li>支持随机化 TTL（防止缓存雪崩）</li>
 * </ol>
 *
 * <p>输出：
 * <ul>
 *   <li>{@link CacheContext#setTtlDecision} 写入 {@link TtlDecision}</li>
 *   <li>{@link ActualCacheHandler#handlePut} / {@link ActualCacheHandler#handlePutIfAbsent}
 *       通过 {@code context.getTtlDecision()} 读取</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@HandlerPriority(HandlerOrder.TTL)
public class TtlHandler extends AbstractCacheHandler {

    private final TtlPolicy ttlPolicy;

    private static final long DEFAULT_TTL = 60;

    /**
     * 语义 counter 元数据声明:TTL jitter 应用事件计数(防雪崩:randomTtl=true
     * 的 variance 展开)。基类负责注册 + null-safe 自增 helper,子类不持有字段
     * 也不写注册样板。
     */
    @Override
    protected CounterMetadata semanticCounter() {
        return new CounterMetadata(
                "resicache.handler.ttl.jittered",
                "TTL jitter applied (avalanche protection: randomTtl=true variance spread the TTL)");
    }

    @Override
    protected boolean shouldHandle(CacheContext context) {
        // 写路径子集谓词,操作枚举承担单一真理源
        return context.getOperation().isWrite();
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        calculateTtl(context);
        // 继续执行后续 Handler
        return HandlerResult.continueChain();
    }

    /**
     * 计算 TTL。
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

            context.setTtlDecision(TtlDecision.applied(finalTtl));

            // TTL jitter 应用计数(randomTtl=true 时 variance 展开)
            if (context.getCacheOperation().isRandomTtl()) {
                safeIncrementSemantic();
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
            context.setTtlDecision(TtlDecision.applied(finalTtl));

            log.debug(
                    "Using parameter TTL: cacheName={}, key={}, ttl={}s",
                    context.getCacheName(),
                    context.getRedisKey(),
                    finalTtl);
        } else {
            // 不应用 TTL（永久缓存）
            context.setTtlDecision(TtlDecision.skipped());

            log.debug(
                    "No TTL applied: cacheName={}, key={}",
                    context.getCacheName(),
                    context.getRedisKey());
        }
    }
}
