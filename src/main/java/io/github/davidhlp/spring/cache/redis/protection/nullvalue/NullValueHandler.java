package io.github.davidhlp.spring.cache.redis.protection.nullvalue;

import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.model.*;


import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

    private final DefaultNullValuePolicy nullValuePolicy;

    /** Path C 后续(WS-1.4) — 空值命中事件计数。 */
    private Counter nullHitCounter;

    public NullValueHandler(DefaultNullValuePolicy nullValuePolicy) {
        this.nullValuePolicy = nullValuePolicy;
    }

    @Override
    protected void onAttachMetrics(MeterRegistry registry) {
        this.nullHitCounter = registerCounter(registry,
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
            safeIncrement(nullHitCounter);
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
