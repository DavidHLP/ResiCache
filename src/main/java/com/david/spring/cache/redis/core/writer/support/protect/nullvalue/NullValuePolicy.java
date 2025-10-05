package com.david.spring.cache.redis.core.writer.support.protect.nullvalue;

import com.david.spring.cache.redis.register.operation.RedisCacheableOperation;

import org.springframework.lang.Nullable;

/** Strategy for handling null cache values consistently. */
public interface NullValuePolicy {

    boolean shouldCacheNull(@Nullable RedisCacheableOperation cacheOperation);

    @Nullable
    Object toStoreValue(@Nullable Object value, @Nullable RedisCacheableOperation cacheOperation);

    @Nullable
    Object fromStoreValue(@Nullable Object storeValue);

    boolean isNullValue(@Nullable Object value);

    @Nullable
    byte[] toReturnValue(@Nullable Object value, String cacheName, String key);
}
