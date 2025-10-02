package com.david.spring.cache.redis.core.writer.support;

import com.david.spring.cache.redis.register.operation.RedisCacheableOperation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/** 空值缓存支持类 提供null值的缓存处理逻辑 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NullValueSupport {

    /**
     * 检查是否应该缓存null值
     *
     * @param cacheOperation 缓存操作配置
     * @return true表示应该缓存null值
     */
    public boolean shouldCacheNull(@Nullable RedisCacheableOperation cacheOperation) {
        return cacheOperation != null && cacheOperation.isCacheNullValues();
    }

    /**
     * 将实际值转换为可缓存的值（如果是null且允许缓存null，则返回null）
     *
     * @param value 实际值
     * @param cacheOperation 缓存操作配置
     * @return 可缓存的值
     */
    @Nullable
    public Object toStoreValue(
            @Nullable Object value, @Nullable RedisCacheableOperation cacheOperation) {
        if (value == null && shouldCacheNull(cacheOperation)) {
            log.debug("Caching null value directly");
            return null;
        }
        return value;
    }

    /**
     * 将缓存中的值转换为实际值
     *
     * @param storeValue 缓存中的值
     * @return 实际值
     */
    @Nullable
    public Object fromStoreValue(@Nullable Object storeValue) {
        return storeValue;
    }

    /**
     * 检查值是否为null
     *
     * @param value 待检查的值
     * @return true表示是null
     */
    public boolean isNullValue(@Nullable Object value) {
        return value == null;
    }
}
