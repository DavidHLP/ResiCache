package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.nullvalue;

import io.github.davidhlp.spring.cache.redis.core.writer.support.type.TypeSupport;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.support.NullValue;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 默认策略，遵循 Spring 缓存对 null 值处理的预期。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultNullValuePolicy implements NullValuePolicy {

    private final TypeSupport typeSupport;

    /**
     * 判断是否应该缓存null值
     *
     * @param cacheOperation 缓存操作配置信息
     * @return 如果应该缓存null值则返回true，否则返回false
     */
    @Override
    public boolean shouldCacheNull(@Nullable RedisCacheableOperation cacheOperation) {
        return cacheOperation != null && cacheOperation.isCacheNullValues();
    }

    /**
     * 将值转换为存储格式
     *
     * @param value 缓存的原始值
     * @param cacheOperation 缓存操作配置信息
     * @return 转换后的存储值
     */
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

    /**
     * 从存储值转换回原始值
     *
     * @param storeValue 存储的值
     * @return 转换后的原始值
     */
    @Override
    @Nullable
    public Object fromStoreValue(@Nullable Object storeValue) {
        return storeValue;
    }

    /**
     * 判断值是否为null值
     *
     * @param value 待判断的值
     * @return 如果是null值则返回true，否则返回false
     */
    @Override
    public boolean isNullValue(@Nullable Object value) {
        return value == null;
    }

    /**
     * 将值转换为返回值的字节数组形式
     *
     * @param value 待转换的值
     * @param cacheName 缓存名称
     * @param key 缓存键
     * @return 转换后的字节数组
     */
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
