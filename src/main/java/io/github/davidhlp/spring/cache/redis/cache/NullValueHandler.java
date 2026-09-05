package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.chain.HandlerOrder;
import io.github.davidhlp.spring.cache.redis.chain.HandlerPriority;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.NullDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 空值处理器
 *
 * <p>职责：
 * <ol>
 *   <li>检查值是否为 null</li>
 *   <li>根据配置决定是否缓存 null 值</li>
 *   <li>转换 null 值为存储格式</li>
 * </ol>
 *
 * <p>输出：
 * <ul>
 *   <li>{@link CacheContext#setNullDecision} 写入 {@link NullDecision}</li>
 *   <li>{@link ActualCacheHandler#handlePut} / {@link ActualCacheHandler#handlePutIfAbsent}
 *       通过 {@code context.getNullDecision()} 读取</li>
 *   <li>当不需要缓存 null 时，返回 {@link HandlerResult#skipAll()}，由
 *       {@code ChainEngine} 物化 {@code CacheContext.skipRemaining=true}</li>
 * </ul>
 */
@Slf4j
@Component
@HandlerPriority(HandlerOrder.NULL_VALUE)
class NullValueHandler extends AbstractCacheHandler {

    private final NullValuePolicy nullValuePolicy;

    public NullValueHandler(NullValuePolicy nullValuePolicy) {
        this.nullValuePolicy = nullValuePolicy;
    }

    /**
     * 语义 counter 元数据声明。基类 {@link AbstractCacheHandler#attachMeterRegistry}
     * 在 registry 非空时从本元数据构建 counter 字段。
     */
    @Override
    protected CounterMetadata semanticCounter() {
        return new CounterMetadata(
                "resicache.handler.null.hit",
                "Null value encountered on PUT (cacheNullValues guard activated, payload is null placeholder)");
    }

    @Override
    protected boolean shouldHandle(CacheContext context) {
        // 写路径子集谓词,与 TtlHandler 共享同一权威源
        return context.getOperation().isWrite();
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        Object deserializedValue = context.getDeserializedValue();

        if (deserializedValue == null) {
            // 空值命中事件计数(覆盖 cacheNullValues=true/false 两种路径)
            safeIncrementSemantic();
            if (!context.policy().cacheNullValues()) {
                log.debug(
                        "Skipping null value caching (cacheNullValues=false): cacheName={}, key={}",
                        context.getCacheName(),
                        context.getRedisKey());
                // 标记跳过后续处理器
                return HandlerResult.skipAll();
            }

            log.debug(
                    "Caching null value: cacheName={}, key={}",
                    context.getCacheName(),
                    context.getRedisKey());
        }

        // 转换值为存储格式(cacheNullValues=true 时空值原样存储,null 由 codec 编码)
        Object storeValue =
                nullValuePolicy.toStoreValue(deserializedValue, context.policy().cacheNullValues());
        context.setNullDecision(NullDecision.of(storeValue));

        // 继续执行后续 Handler
        return HandlerResult.continueChain();
    }
}
