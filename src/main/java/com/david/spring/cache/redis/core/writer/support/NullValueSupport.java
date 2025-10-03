package com.david.spring.cache.redis.core.writer.support;

import com.david.spring.cache.redis.register.operation.RedisCacheableOperation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.support.NullValue;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/** 空值缓存支持类 提供null值的缓存处理逻辑 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NullValueSupport {

    private final TypeSupport typeSupport;

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

    /**
     * 将缓存值转换为返回给Spring的字节数组 如果值为null，则序列化为Spring期望的NullValue格式
     *
     * @param value 缓存值
     * @param cacheName 缓存名称
     * @param key 缓存key
     * @return 序列化后的字节数组
     */
    @Nullable
    public byte[] toReturnValue(@Nullable Object value, String cacheName, String key) {
        if (isNullValue(value)) {
            // 对于null值，使用标准的NullValue序列化格式，确保Spring能正确识别
            byte[] result = typeSupport.serializeToBytes(NullValue.INSTANCE);
            log.debug(
                    "Returning null value in standard format: cacheName={}, key={}",
                    cacheName,
                    key);
            return result;
        }
        return typeSupport.serializeToBytes(value);
    }
}
