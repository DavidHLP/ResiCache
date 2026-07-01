package io.github.davidhlp.spring.cache.redis.protection.nullvalue;

import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.model.*;


import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 空值处理器
 * 
 * 职责：
 * 1. 检查值是否为 null
 * 2. 根据配置决定是否缓存 null 值
 * 3. 转换 null 值为存储格式
 * 
 * 输出（设置到 CacheOutput）：
 * - storeValue: 转换后的存储值
 * - skipRemaining: 如果不缓存 null，标记跳过后续处理器
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
        return context.getOperation() == CacheOperation.PUT
                || context.getOperation() == CacheOperation.PUT_IF_ABSENT;
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
        context.getOutput().setStoreValue(storeValue);

        // 继续执行后续 Handler
        return HandlerResult.continueChain();
    }
}
