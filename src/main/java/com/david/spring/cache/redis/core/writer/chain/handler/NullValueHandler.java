package com.david.spring.cache.redis.core.writer.chain.handler;

import com.david.spring.cache.redis.core.writer.chain.CacheOperation;
import com.david.spring.cache.redis.core.writer.chain.CacheResult;
import com.david.spring.cache.redis.core.writer.support.protect.nullvalue.NullValuePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Null value handler applying the configured null caching policy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NullValueHandler extends AbstractCacheHandler {

    private final NullValuePolicy nullValuePolicy;

    @Override
    protected boolean shouldHandle(CacheContext context) {
        return context.getOperation() == CacheOperation.PUT
                || context.getOperation() == CacheOperation.PUT_IF_ABSENT;
    }

    @Override
    protected CacheResult doHandle(CacheContext context) {
        Object deserializedValue = context.getDeserializedValue();

        if (deserializedValue == null) {
            if (!nullValuePolicy.shouldCacheNull(context.getCacheOperation())) {
                log.debug(
                        "Skipping null value caching (cacheNullValues=false): cacheName={}, key={}",
                        context.getCacheName(),
                        context.getRedisKey());
                context.setSkipRemaining(true);
                return CacheResult.success();
            }

            log.debug(
                    "Caching null value: cacheName={}, key={}",
                    context.getCacheName(),
                    context.getRedisKey());
        }

        Object storeValue =
                nullValuePolicy.toStoreValue(deserializedValue, context.getCacheOperation());
        context.setStoreValue(storeValue);

        return invokeNext(context);
    }
}