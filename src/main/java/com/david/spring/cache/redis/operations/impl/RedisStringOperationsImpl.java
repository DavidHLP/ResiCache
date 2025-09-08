package com.david.spring.cache.redis.operations.impl;

import com.david.spring.cache.redis.operations.RedisStringOperations;
import com.david.spring.cache.redis.serialization.RedisSerialization;

import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis 字符串操作实现类
 *
 * <p>提供链式调用和方法级泛型的强类型读取能力
 *
 * @author David
 */
@Slf4j
public class RedisStringOperationsImpl implements RedisStringOperations {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisSerialization serializationHelper;

    public RedisStringOperationsImpl(
            RedisTemplate<String, Object> redisTemplate, RedisSerialization serializationHelper) {
        this.redisTemplate = redisTemplate;
        this.serializationHelper = serializationHelper;

        // 确保使用正确的序列化器
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(serializationHelper.getSerializer());
    }

    @Override
    public <T> void set(String key, T value, Class<T> type) {
        redisTemplate.opsForValue().set(key, value);
        log.debug("设置键: {}，类型: {}", key, type.getSimpleName());
    }

    @Override
    public <T> Boolean setIfAbsent(String key, T value, Class<T> type) {
        Boolean result = redisTemplate.opsForValue().setIfAbsent(key, value);
        log.debug("设置键(仅当不存在时): {}，类型: {}，结果: {}", key, type.getSimpleName(), result);
        return result;
    }

    @Override
    public <T> Boolean setIfPresent(String key, T value, Class<T> type) {
        Boolean result = redisTemplate.opsForValue().setIfPresent(key, value);
        log.debug("设置键(仅当存在时): {}，类型: {}，结果: {}", key, type.getSimpleName(), result);
        return result;
    }

    @Override
    public <T> void set(String key, T value, Class<T> type, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
        log.debug("设置键: {}，类型: {}，超时时间: {} {}", key, type.getSimpleName(), timeout, unit);
    }

    @Override
    public <T> void set(String key, T value, Class<T> type, Duration duration) {
        redisTemplate.opsForValue().set(key, value, duration);
        log.debug("设置键: {}，类型: {}，持续时间: {}", key, type.getSimpleName(), duration);
    }

    /**
     * 设置键值对和过期时间（支持Object类型和通配符Class类型）
     * 用于缓存场景，其中值类型可能是Object，类型是Class<?>
     */
    public void setWithWildcardType(String key, Object value, Class<?> type, Duration duration) {
        redisTemplate.opsForValue().set(key, value, duration);
        log.debug("设置键: {}，类型: {}，持续时间: {}", key, type.getSimpleName(), duration);
    }
    
    /**
     * 设置键值对（支持Object类型和通配符Class类型）
     * 用于缓存场景，其中值类型可能是Object，类型是Class<?>
     */
    public void setWithWildcardType(String key, Object value, Class<?> type) {
        redisTemplate.opsForValue().set(key, value);
        log.debug("设置键: {}，类型: {}", key, type.getSimpleName());
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        Object result = redisTemplate.opsForValue().get(key);
        if (result == null) {
            log.debug("未找到键: {}", key);
            return null;
        }

        T convertedResult = serializationHelper.convertValue(result, type);
        log.debug("获取键: {}，类型: {}，找到值: {}", key, type.getSimpleName(), convertedResult != null);
        return convertedResult;
    }

    @Override
    public <T> T getAndSet(String key, T value, Class<T> type) {
        Object oldValue = redisTemplate.opsForValue().getAndSet(key, value);
        if (oldValue == null) {
            return null;
        }

        T convertedOldValue = serializationHelper.convertValue(oldValue, type);
        log.debug(
                "获取并设置键: {}，类型: {}，旧值存在: {}", key, type.getSimpleName(), convertedOldValue != null);
        return convertedOldValue;
    }

    @Override
    public <T> List<T> multiGet(Collection<String> keys, Class<T> type) {
        List<Object> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) {
            return Collections.emptyList();
        }

        List<T> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value == null) {
                result.add(null);
            } else {
                T convertedValue = serializationHelper.convertValue(value, type);
                result.add(convertedValue);
            }
        }
        log.debug("批量获取 {} 个键，类型: {}", keys.size(), type.getSimpleName());
        return result;
    }

    @Override
    public <T> void multiSet(Map<String, T> map, Class<T> type) {
        if (map == null || map.isEmpty()) {
            return;
        }

        // 将值转换为 Object 类型
        Map<String, Object> objectMap = new HashMap<>(map.size());
        for (Map.Entry<String, T> entry : map.entrySet()) {
            objectMap.put(entry.getKey(), entry.getValue());
        }

        redisTemplate.opsForValue().multiSet(objectMap);
        log.debug("批量设置 {} 个键，类型: {}", map.size(), type.getSimpleName());
    }

    @Override
    public <T> Boolean multiSetIfAbsent(Map<String, T> map, Class<T> type) {
        if (map == null || map.isEmpty()) {
            return false;
        }

        // 将值转换为 Object 类型
        Map<String, Object> objectMap = new HashMap<>(map.size());
        for (Map.Entry<String, T> entry : map.entrySet()) {
            objectMap.put(entry.getKey(), entry.getValue());
        }

        Boolean result = redisTemplate.opsForValue().multiSetIfAbsent(objectMap);
        log.debug("批量设置键(仅当都不存在时): {}，类型: {}，结果: {}", map.size(), type.getSimpleName(), result);
        return result;
    }

    @Override
    public Long increment(String key, long delta) {
        Long result = redisTemplate.opsForValue().increment(key, delta);
        log.debug("增加键: {}，增量: {}，结果: {}", key, delta, result);
        return result;
    }

    @Override
    public Double increment(String key, double delta) {
        Double result = redisTemplate.opsForValue().increment(key, delta);
        log.debug("增加键: {}，增量: {}，结果: {}", key, delta, result);
        return result;
    }

    @Override
    public Long decrement(String key, long delta) {
        Long result = redisTemplate.opsForValue().decrement(key, delta);
        log.debug("减少键: {}，减量: {}，结果: {}", key, delta, result);
        return result;
    }

    @Override
    public Boolean delete(String key) {
        Boolean result = redisTemplate.delete(key);
        log.debug("删除键: {}，结果: {}", key, result);
        return result;
    }

    @Override
    public Long delete(Collection<String> keys) {
        Long result = redisTemplate.delete(keys);
        log.debug("删除 {} 个键，结果: {}", keys.size(), result);
        return result;
    }

    @Override
    public Boolean hasKey(String key) {
        Boolean result = redisTemplate.hasKey(key);
        log.debug("检查键是否存在: {}，结果: {}", key, result);
        return result;
    }

    @Override
    public Boolean expire(String key, long timeout, TimeUnit unit) {
        Boolean result = redisTemplate.expire(key, timeout, unit);
        log.debug("设置键过期时间: {}，超时: {} {}，结果: {}", key, timeout, unit, result);
        return result;
    }

    @Override
    public Boolean expire(String key, Duration duration) {
        Boolean result = redisTemplate.expire(key, duration);
        log.debug("设置键过期时间: {}，持续时间: {}，结果: {}", key, duration, result);
        return result;
    }

    @Override
    public Long getExpire(String key, TimeUnit unit) {
        Long result = redisTemplate.getExpire(key, unit);
        log.debug("获取键过期时间: {}，单位: {}，结果: {}", key, unit, result);
        return result;
    }

    @Override
    public Duration getExpire(String key) {
        Long seconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (seconds == null || seconds < 0) {
            return Duration.ofSeconds(-1);
        }
        return Duration.ofSeconds(seconds);
    }
}
