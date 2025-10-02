package com.david.spring.cache.redis.core.writer.support;

import com.david.spring.cache.redis.register.operation.RedisCacheableOperation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/** 空值缓存支持类 提供null值的缓存处理逻辑 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NullValueSupport {

    /** NULL值标记对象，用于在缓存中表示null值 */
    private static final NullValue NULL_VALUE = new NullValue();

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
     * 将实际值转换为可缓存的值（如果是null且允许缓存null，则返回NULL_VALUE标记）
     *
     * @param value 实际值
     * @param cacheOperation 缓存操作配置
     * @return 可缓存的值
     */
    @Nullable
    public Object toStoreValue(
            @Nullable Object value, @Nullable RedisCacheableOperation cacheOperation) {
        if (value == null && shouldCacheNull(cacheOperation)) {
            log.debug("Caching null value as NULL_VALUE marker");
            return NULL_VALUE;
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
        if (storeValue instanceof NullValue) {
            return null;
        }
        return storeValue;
    }

    /**
     * 检查值是否为null或NULL_VALUE标记
     *
     * @param value 待检查的值
     * @return true表示是null或NULL_VALUE标记
     */
    public boolean isNullValue(@Nullable Object value) {
        return value == null || value instanceof NullValue;
    }

    /** NULL值标记类 用于在缓存中表示null值，避免与"缓存未命中"混淆 */
    private static class NullValue implements Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean equals(Object obj) {
            return this == obj || obj instanceof NullValue;
        }

        @Override
        public int hashCode() {
            return NullValue.class.hashCode();
        }

        @Override
        public String toString() {
            return "NullValue";
        }
    }
}
