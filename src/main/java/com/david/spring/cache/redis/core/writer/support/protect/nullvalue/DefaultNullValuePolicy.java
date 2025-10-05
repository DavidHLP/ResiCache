package com.david.spring.cache.redis.core.writer.support.protect.nullvalue;

import com.david.spring.cache.redis.core.writer.support.type.TypeSupport;
import com.david.spring.cache.redis.register.operation.RedisCacheableOperation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.support.NullValue;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/** Default policy that mirrors Spring cache null handling expectations. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultNullValuePolicy implements NullValuePolicy {

    private final TypeSupport typeSupport;

    @Override
    public boolean shouldCacheNull(@Nullable RedisCacheableOperation cacheOperation) {
        return cacheOperation != null && cacheOperation.isCacheNullValues();
    }

    @Override
    @Nullable
    public Object toStoreValue(
            @Nullable Object value, @Nullable RedisCacheableOperation cacheOperation) {
        if (value == null && shouldCacheNull(cacheOperation)) {
            log.debug("Caching null value directly");
            return null;
        }
        return value;
    }

    @Override
    @Nullable
    public Object fromStoreValue(@Nullable Object storeValue) {
        return storeValue;
    }

    @Override
    public boolean isNullValue(@Nullable Object value) {
        return value == null;
    }

    @Override
    @Nullable
    public byte[] toReturnValue(@Nullable Object value, String cacheName, String key) {
        if (isNullValue(value)) {
            byte[] result = typeSupport.serializeToBytes(NullValue.INSTANCE);
            log.debug(
                    "Returning null value in standard format: cacheName={}, key={}",
                    cacheName,
                    key);
            return result;
        }
        if (value != null) {
            return typeSupport.serializeToBytes(value);
        }
        return null;
    }
}
