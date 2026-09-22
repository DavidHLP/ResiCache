package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.chain.ChainContinuation;
import io.github.davidhlp.spring.cache.redis.chain.HandlerOrder;
import io.github.davidhlp.spring.cache.redis.chain.HandlerPriority;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView;
import io.github.davidhlp.spring.cache.redis.chain.model.TtlDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * TTL 处理器
 *
 * <p>职责：
 * <ol>
 *   <li>把 {@link TtlPolicy} 解析出的 TTL 决策写入上下文(优先级与默认值归 TtlPolicy 所有)</li>
 *   <li>按决策来源输出 debug 日志、自增 TTL 抖动计数</li>
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
@HandlerPriority(HandlerOrder.TTL)
class TtlHandler extends AbstractCacheHandler {

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
    protected HandlerResult doHandle(CacheContext context, ChainContinuation next) {
        CachePolicyView policy = context.policy();
        TtlPolicy.Resolution resolution = TtlPolicy.resolve(context.getTtl(), policy);
        context.setTtlDecision(resolution.decision());

        // TTL jitter 应用计数(注解路径 + randomTtl=true 时)
        if (resolution.jitterRequested()) {
            safeIncrementSemantic();
        }

        switch (resolution.source()) {
            case ANNOTATION -> log.debug(
                    "Using context TTL configuration: cacheName={}, key={}, baseTtl={}s, finalTtl={}s, randomTtl={}, variance={}",
                    context.getCacheName(),
                    context.getRedisKey(),
                    policy.ttl(),
                    resolution.decision().finalTtl(),
                    policy.randomTtl(),
                    policy.variance());
            case PARAMETER -> log.debug(
                    "Using parameter TTL: cacheName={}, key={}, ttl={}s",
                    context.getCacheName(),
                    context.getRedisKey(),
                    resolution.decision().finalTtl());
            case NONE -> log.debug(
                    "No TTL applied: cacheName={}, key={}",
                    context.getCacheName(),
                    context.getRedisKey());
        }

        // 继续执行后续 Handler
        return HandlerResult.continueChain();
    }
}
