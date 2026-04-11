package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.nullvalue.NullValuePolicy;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
@HandlerPriority(HandlerOrder.NULL_VALUE)
public class NullValueHandler extends AbstractCacheHandler {

    private final NullValuePolicy nullValuePolicy;

    @Override
    protected boolean shouldHandle(CacheContext context) {
        return context.getOperation() == CacheOperation.PUT
                || context.getOperation() == CacheOperation.PUT_IF_ABSENT;
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        Object deserializedValue = context.getDeserializedValue();

        if (deserializedValue == null) {
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
