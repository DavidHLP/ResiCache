package io.github.davidhlp.spring.cache.redis.protection.nullvalue;

import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.model.*;


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
 * <p>输出（ADR-0033 类型化决策 — 替代原 {@code CacheOutput.storeValue} 单字段共享袋）：
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
public class NullValueHandler extends AbstractCacheHandler {

    private final NullValuePolicy nullValuePolicy;

    public NullValueHandler(NullValuePolicy nullValuePolicy) {
        this.nullValuePolicy = nullValuePolicy;
    }

    /**
     * ADR-0018 — 语义 counter 元数据声明。基类 {@link AbstractCacheHandler#attachMeterRegistry}
     * 在 registry 非空时从本元数据构建 counter 字段；子类不再写"取 registry 调
     * registerCounter 存到本类字段"5 行样板，也不再持有 null-prone 字段。
     */
    @Override
    protected CounterMetadata semanticCounter() {
        return new CounterMetadata(
                "resicache.handler.null.hit",
                "Null value encountered on PUT (cacheNullValues guard activated, payload is null placeholder)");
    }

    @Override
    protected boolean shouldHandle(CacheContext context) {
        // ADR-0054:写路径子集谓词,与 TtlHandler 共享同一权威源
        return context.getOperation().isWrite();
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        Object deserializedValue = context.getDeserializedValue();

        if (deserializedValue == null) {
            // WS-1.4 per-handler tag:空值命中事件计数(覆盖 cacheNullValues=true/false 两种路径)
            safeIncrementSemantic();
            if (!nullValuePolicy.shouldCacheNull(context.getCacheOperation())) {
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

        // 转换值为存储格式
        Object storeValue =
                nullValuePolicy.toStoreValue(deserializedValue, context.getCacheOperation());
        context.setNullDecision(NullDecision.of(storeValue));

        // 继续执行后续 Handler
        return HandlerResult.continueChain();
    }
}
