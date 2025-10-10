package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.nullvalue;

import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
import org.springframework.lang.Nullable;

/**
 * 用于一致处理空缓存值的策略。
 */
public interface NullValuePolicy {

    /**
     * 判断是否应该缓存空值
     * @param cacheOperation 缓存操作信息
     * @return 如果应该缓存空值则返回true，否则返回false
     */
    boolean shouldCacheNull(@Nullable RedisCacheableOperation cacheOperation);

    /**
     * 将值转换为可存储的格式
     * @param value 原始值
     * @param cacheOperation 缓存操作信息
     * @return 转换后的可存储值
     */
    @Nullable
    Object toStoreValue(@Nullable Object value, @Nullable RedisCacheableOperation cacheOperation);

    /**
     * 从存储值转换回原始值
     * @param storeValue 存储中的值
     * @return 转换后的原始值
     */
    @Nullable
    Object fromStoreValue(@Nullable Object storeValue);

    /**
     * 判断给定值是否为空值
     * @param value 要检查的值
     * @return 如果是空值则返回true，否则返回false
     */
    boolean isNullValue(@Nullable Object value);

    /**
     * 将值转换为返回值的字节数组形式
     * @param value 值
     * @param cacheName 缓存名称
     * @param key 缓存键
     * @return 返回值的字节数组表示
     */
    @Nullable
    byte[] toReturnValue(@Nullable Object value, String cacheName, String key);
}
