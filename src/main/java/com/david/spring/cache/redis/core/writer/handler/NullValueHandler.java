package com.david.spring.cache.redis.core.writer.handler;

import com.david.spring.cache.redis.core.writer.support.NullValueSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 空值处理器 职责：处理 null 值的缓存逻辑 - PUT/PUT_IF_ABSENT 操作：判断是否应该缓存 null，并转换为存储格式 - GET
 * 操作：将存储的 null 值转换为返回格式
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NullValueHandler extends AbstractCacheHandler {

    private final NullValueSupport nullValueSupport;

    @Override
    protected boolean shouldHandle(CacheContext context) {
        // PUT/PUT_IF_ABSENT 操作时都需要处理（不仅仅是 null 值）
        return context.getOperation() == CacheOperation.PUT
                || context.getOperation() == CacheOperation.PUT_IF_ABSENT;
    }

    @Override
    protected CacheResult doHandle(CacheContext context) {
        Object deserializedValue = context.getDeserializedValue();

        // 如果值为 null，检查是否应该缓存
        if (deserializedValue == null) {
            if (!nullValueSupport.shouldCacheNull(context.getCacheOperation())) {
                log.debug(
                        "Skipping null value caching (cacheNullValues=false): cacheName={}, key={}",
                        context.getCacheName(),
                        context.getRedisKey());
                // 不缓存 null，直接返回成功（不继续责任链）
                context.setSkipRemaining(true);
                return CacheResult.success();
            }

            log.debug(
                    "Caching null value: cacheName={}, key={}",
                    context.getCacheName(),
                    context.getRedisKey());
        }

        // 将值转换为可存储的格式（null 或非 null 都需要转换）
        Object storeValue =
                nullValueSupport.toStoreValue(deserializedValue, context.getCacheOperation());
        context.setStoreValue(storeValue);

        // 继续责任链
        return invokeNext(context);
    }
}
