package com.david.spring.cache.redis.operations;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis 字符串操作接口
 * 
 * <p>提供类型安全的 Redis 字符串操作，支持自定义过期时间和类型指定
 * 
 * @author David
 */
public interface RedisStringOperations {

    /**
     * 设置键值对
     * 
     * @param key   键
     * @param value 值
     * @param type  值类型
     * @param <T>   值类型泛型
     */
    <T> void set(String key, T value, Class<T> type);

    /**
     * 设置键值对，如果键不存在
     * 
     * @param key   键
     * @param value 值
     * @param type  值类型
     * @param <T>   值类型泛型
     * @return 设置成功返回 true，键已存在返回 false
     */
    <T> Boolean setIfAbsent(String key, T value, Class<T> type);

    /**
     * 设置键值对，如果键存在
     * 
     * @param key   键
     * @param value 值
     * @param type  值类型
     * @param <T>   值类型泛型
     * @return 设置成功返回 true，键不存在返回 false
     */
    <T> Boolean setIfPresent(String key, T value, Class<T> type);

    /**
     * 设置键值对并指定过期时间
     * 
     * @param key     键
     * @param value   值
     * @param type    值类型
     * @param timeout 过期时间
     * @param unit    时间单位
     * @param <T>     值类型泛型
     */
    <T> void set(String key, T value, Class<T> type, long timeout, TimeUnit unit);

    /**
     * 设置键值对并指定过期时间
     * 
     * @param key      键
     * @param value    值
     * @param type     值类型
     * @param duration 过期时间
     * @param <T>      值类型泛型
     */
    <T> void set(String key, T value, Class<T> type, Duration duration);

    /**
     * 获取值
     * 
     * @param key  键
     * @param type 值类型
     * @param <T>  值类型泛型
     * @return 值，不存在返回 null
     */
    <T> T get(String key, Class<T> type);

    /**
     * 获取并设置新值
     * 
     * @param key   键
     * @param value 新值
     * @param type  值类型
     * @param <T>   值类型泛型
     * @return 旧值，不存在返回 null
     */
    <T> T getAndSet(String key, T value, Class<T> type);

    /**
     * 批量获取值
     * 
     * @param keys 键集合
     * @param type 值类型
     * @param <T>  值类型泛型
     * @return 值列表，不存在的键对应 null
     */
    <T> List<T> multiGet(Collection<String> keys, Class<T> type);

    /**
     * 批量设置键值对
     * 
     * @param map  键值对映射
     * @param type 值类型
     * @param <T>  值类型泛型
     */
    <T> void multiSet(Map<String, T> map, Class<T> type);

    /**
     * 批量设置键值对，如果所有键都不存在
     * 
     * @param map  键值对映射
     * @param type 值类型
     * @param <T>  值类型泛型
     * @return 设置成功返回 true，任一键已存在返回 false
     */
    <T> Boolean multiSetIfAbsent(Map<String, T> map, Class<T> type);

    /**
     * 增加数值
     * 
     * @param key   键
     * @param delta 增量
     * @return 增加后的值
     */
    Long increment(String key, long delta);

    /**
     * 增加浮点数值
     * 
     * @param key   键
     * @param delta 增量
     * @return 增加后的值
     */
    Double increment(String key, double delta);

    /**
     * 减少数值
     * 
     * @param key   键
     * @param delta 减量
     * @return 减少后的值
     */
    Long decrement(String key, long delta);

    /**
     * 删除键
     * 
     * @param key 键
     * @return 删除成功返回 true
     */
    Boolean delete(String key);

    /**
     * 批量删除键
     * 
     * @param keys 键集合
     * @return 删除的键数量
     */
    Long delete(Collection<String> keys);

    /**
     * 检查键是否存在
     * 
     * @param key 键
     * @return 存在返回 true
     */
    Boolean hasKey(String key);

    /**
     * 设置过期时间
     * 
     * @param key     键
     * @param timeout 过期时间
     * @param unit    时间单位
     * @return 设置成功返回 true
     */
    Boolean expire(String key, long timeout, TimeUnit unit);

    /**
     * 设置过期时间
     * 
     * @param key      键
     * @param duration 过期时间
     * @return 设置成功返回 true
     */
    Boolean expire(String key, Duration duration);

    /**
     * 获取过期时间
     * 
     * @param key  键
     * @param unit 时间单位
     * @return 过期时间，-1 表示永不过期，-2 表示键不存在
     */
    Long getExpire(String key, TimeUnit unit);

    /**
     * 获取过期时间
     * 
     * @param key 键
     * @return 过期时间，负值表示永不过期或键不存在
     */
    Duration getExpire(String key);
}
