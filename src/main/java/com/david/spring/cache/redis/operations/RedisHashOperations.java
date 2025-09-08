package com.david.spring.cache.redis.operations;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 哈希操作接口
 * 
 * <p>提供类型安全的 Redis 哈希操作，支持自定义过期时间和类型指定
 * 
 * @author David
 */
public interface RedisHashOperations {

    /**
     * 设置哈希字段值
     * 
     * @param key       键
     * @param hashKey   哈希字段
     * @param value     值
     * @param valueType 值类型
     * @param <T>       值类型泛型
     */
    <T> void put(String key, Object hashKey, T value, Class<T> valueType);

    /**
     * 批量设置哈希字段值
     * 
     * @param key       键
     * @param map       字段值映射
     * @param valueType 值类型
     * @param <T>       值类型泛型
     */
    <T> void putAll(String key, Map<Object, T> map, Class<T> valueType);

    /**
     * 设置哈希字段值，如果字段不存在
     * 
     * @param key       键
     * @param hashKey   哈希字段
     * @param value     值
     * @param valueType 值类型
     * @param <T>       值类型泛型
     * @return 设置成功返回 true，字段已存在返回 false
     */
    <T> Boolean putIfAbsent(String key, Object hashKey, T value, Class<T> valueType);

    /**
     * 获取哈希字段值
     * 
     * @param key       键
     * @param hashKey   哈希字段
     * @param valueType 值类型
     * @param <T>       值类型泛型
     * @return 字段值，不存在返回 null
     */
    <T> T get(String key, Object hashKey, Class<T> valueType);

    /**
     * 批量获取哈希字段值
     * 
     * @param key       键
     * @param hashKeys  哈希字段集合
     * @param valueType 值类型
     * @param <T>       值类型泛型
     * @return 字段值列表，不存在的字段对应 null
     */
    <T> List<T> multiGet(String key, Collection<Object> hashKeys, Class<T> valueType);

    /**
     * 获取哈希所有字段值
     * 
     * @param key       键
     * @param valueType 值类型
     * @param <T>       值类型泛型
     * @return 所有字段值列表
     */
    <T> List<T> values(String key, Class<T> valueType);

    /**
     * 获取哈希所有字段
     * 
     * @param key 键
     * @return 所有字段集合
     */
    Set<Object> keys(String key);

    /**
     * 获取哈希所有字段和值
     * 
     * @param key       键
     * @param valueType 值类型
     * @param <T>       值类型泛型
     * @return 字段值映射
     */
    <T> Map<Object, T> entries(String key, Class<T> valueType);

    /**
     * 删除哈希字段
     * 
     * @param key      键
     * @param hashKeys 哈希字段
     * @return 删除的字段数量
     */
    Long delete(String key, Object... hashKeys);

    /**
     * 检查哈希字段是否存在
     * 
     * @param key     键
     * @param hashKey 哈希字段
     * @return 存在返回 true
     */
    Boolean hasKey(String key, Object hashKey);

    /**
     * 获取哈希字段数量
     * 
     * @param key 键
     * @return 字段数量
     */
    Long size(String key);

    /**
     * 检查哈希是否为空
     * 
     * @param key 键
     * @return 为空返回 true
     */
    default Boolean isEmpty(String key) {
        Long size = size(key);
        return size == null || size == 0;
    }

    /**
     * 增加哈希字段的数值
     * 
     * @param key     键
     * @param hashKey 哈希字段
     * @param delta   增量
     * @return 增加后的值
     */
    Long increment(String key, Object hashKey, long delta);

    /**
     * 增加哈希字段的浮点数值
     * 
     * @param key     键
     * @param hashKey 哈希字段
     * @param delta   增量
     * @return 增加后的值
     */
    Double increment(String key, Object hashKey, double delta);

    /**
     * 减少哈希字段的数值
     * 
     * @param key     键
     * @param hashKey 哈希字段
     * @param delta   减量
     * @return 减少后的值
     */
    Long decrement(String key, Object hashKey, long delta);

    /**
     * 删除整个哈希
     * 
     * @param key 键
     * @return 删除成功返回 true
     */
    Boolean delete(String key);

    /**
     * 批量删除哈希
     * 
     * @param keys 键集合
     * @return 删除的键数量
     */
    Long delete(Collection<String> keys);

    /**
     * 检查哈希键是否存在
     * 
     * @param key 键
     * @return 存在返回 true
     */
    Boolean hasKey(String key);

    /**
     * 设置哈希过期时间
     * 
     * @param key     键
     * @param timeout 过期时间
     * @param unit    时间单位
     * @return 设置成功返回 true
     */
    Boolean expire(String key, long timeout, TimeUnit unit);

    /**
     * 设置哈希过期时间
     * 
     * @param key      键
     * @param duration 过期时间
     * @return 设置成功返回 true
     */
    Boolean expire(String key, Duration duration);

    /**
     * 获取哈希过期时间
     * 
     * @param key  键
     * @param unit 时间单位
     * @return 过期时间，-1 表示永不过期，-2 表示键不存在
     */
    Long getExpire(String key, TimeUnit unit);

    /**
     * 获取哈希过期时间
     * 
     * @param key 键
     * @return 过期时间，负值表示永不过期或键不存在
     */
    Duration getExpire(String key);

    /**
     * 扫描哈希字段
     * 
     * @param key       键
     * @param pattern   匹配模式
     * @param count     扫描数量
     * @param valueType 值类型
     * @param <T>       值类型泛型
     * @return 匹配的字段值映射
     */
    <T> Map<Object, T> scan(String key, String pattern, long count, Class<T> valueType);
}
