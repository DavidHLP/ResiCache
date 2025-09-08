package com.david.spring.cache.redis.operations.impl;

import com.david.spring.cache.redis.operations.RedisHashOperations;
import com.david.spring.cache.redis.serialization.RedisSerialization;

import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis 哈希操作实现类
 *
 * <p>提供链式调用和方法级泛型的强类型读取能力
 *
 * @author David
 */
@Slf4j
public class RedisHashOperationsImpl implements RedisHashOperations {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisSerialization serializationHelper;

    public RedisHashOperationsImpl(
            RedisTemplate<String, Object> redisTemplate, RedisSerialization serializationHelper) {
        this.redisTemplate = redisTemplate;
        this.serializationHelper = serializationHelper;

        // 确保使用正确的序列化器
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(serializationHelper.getSerializer());
    }

    @Override
    public <T> void put(String key, Object hashKey, T value, Class<T> valueType) {
        redisTemplate.opsForHash().put(key, hashKey, value);
        log.debug("设置哈希字段: {}[{}]，类型: {}", key, hashKey, valueType.getSimpleName());
    }

    @Override
    public <T> void putAll(String key, Map<Object, T> map, Class<T> valueType) {
        if (map == null || map.isEmpty()) {
            return;
        }

        // 将值转换为 Object 类型
        Map<Object, Object> objectMap = new HashMap<>(map.size());
        for (Map.Entry<Object, T> entry : map.entrySet()) {
            objectMap.put(entry.getKey(), entry.getValue());
        }

        redisTemplate.opsForHash().putAll(key, objectMap);
        log.debug("批量设置哈希字段: {}，字段数量: {}，类型: {}", key, map.size(), valueType.getSimpleName());
    }

    @Override
    public <T> Boolean putIfAbsent(String key, Object hashKey, T value, Class<T> valueType) {
        Boolean result = redisTemplate.opsForHash().putIfAbsent(key, hashKey, value);
        log.debug("设置哈希字段(仅当不存在时): {}[{}]，类型: {}，结果: {}", key, hashKey, valueType.getSimpleName(), result);
        return result;
    }

    @Override
    public <T> T get(String key, Object hashKey, Class<T> valueType) {
        Object result = redisTemplate.opsForHash().get(key, hashKey);
        if (result == null) {
            log.debug("未找到哈希字段: {}[{}]", key, hashKey);
            return null;
        }

        T convertedResult = serializationHelper.convertValue(result, valueType);
        log.debug("获取哈希字段: {}[{}]，类型: {}，找到值: {}", key, hashKey, valueType.getSimpleName(), convertedResult != null);
        return convertedResult;
    }

    @Override
    public <T> List<T> multiGet(String key, Collection<Object> hashKeys, Class<T> valueType) {
        List<Object> values = redisTemplate.opsForHash().multiGet(key, hashKeys);
        if (values == null) {
            return Collections.emptyList();
        }

        List<T> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value == null) {
                result.add(null);
            } else {
                T convertedValue = serializationHelper.convertValue(value, valueType);
                result.add(convertedValue);
            }
        }
        log.debug("批量获取哈希字段: {}，字段数量: {}，类型: {}", key, hashKeys.size(), valueType.getSimpleName());
        return result;
    }

    @Override
    public <T> List<T> values(String key, Class<T> valueType) {
        List<Object> values = redisTemplate.opsForHash().values(key);
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }

        List<T> result = new ArrayList<>(values.size());
        for (Object value : values) {
            T convertedValue = serializationHelper.convertValue(value, valueType);
            result.add(convertedValue);
        }
        log.debug("获取哈希所有值: {}，值数量: {}，类型: {}", key, values.size(), valueType.getSimpleName());
        return result;
    }

    @Override
    public Set<Object> keys(String key) {
        Set<Object> keys = redisTemplate.opsForHash().keys(key);
        log.debug("获取哈希所有字段: {}，字段数量: {}", key, keys != null ? keys.size() : 0);
        return keys != null ? keys : Collections.emptySet();
    }

    @Override
    public <T> Map<Object, T> entries(String key, Class<T> valueType) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Object, T> result = new HashMap<>(entries.size());
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            T convertedValue = serializationHelper.convertValue(entry.getValue(), valueType);
            result.put(entry.getKey(), convertedValue);
        }
        log.debug("获取哈希所有字段和值: {}，条目数量: {}，类型: {}", key, entries.size(), valueType.getSimpleName());
        return result;
    }

    @Override
    public Long delete(String key, Object... hashKeys) {
        Long result = redisTemplate.opsForHash().delete(key, hashKeys);
        log.debug("删除哈希字段: {}，字段数量: {}，结果: {}", key, hashKeys.length, result);
        return result;
    }

    @Override
    public Boolean hasKey(String key, Object hashKey) {
        Boolean result = redisTemplate.opsForHash().hasKey(key, hashKey);
        log.debug("检查哈希字段是否存在: {}[{}]，结果: {}", key, hashKey, result);
        return result;
    }

    @Override
    public Long size(String key) {
        Long result = redisTemplate.opsForHash().size(key);
        log.debug("获取哈希大小: {}，结果: {}", key, result);
        return result;
    }

    @Override
    public Long increment(String key, Object hashKey, long delta) {
        Long result = redisTemplate.opsForHash().increment(key, hashKey, delta);
        log.debug("增加哈希字段数值: {}[{}]，增量: {}，结果: {}", key, hashKey, delta, result);
        return result;
    }

    @Override
    public Double increment(String key, Object hashKey, double delta) {
        Double result = redisTemplate.opsForHash().increment(key, hashKey, delta);
        log.debug("增加哈希字段浮点数值: {}[{}]，增量: {}，结果: {}", key, hashKey, delta, result);
        return result;
    }

    @Override
    public Long decrement(String key, Object hashKey, long delta) {
        Long result = redisTemplate.opsForHash().increment(key, hashKey, -delta);
        log.debug("减少哈希字段数值: {}[{}]，减量: {}，结果: {}", key, hashKey, delta, result);
        return result;
    }

    @Override
    public Boolean delete(String key) {
        Boolean result = redisTemplate.delete(key);
        log.debug("删除哈希: {}，结果: {}", key, result);
        return result;
    }

    @Override
    public Long delete(Collection<String> keys) {
        Long result = redisTemplate.delete(keys);
        log.debug("删除 {} 个哈希，结果: {}", keys.size(), result);
        return result;
    }

    @Override
    public Boolean hasKey(String key) {
        Boolean result = redisTemplate.hasKey(key);
        log.debug("检查哈希键是否存在: {}，结果: {}", key, result);
        return result;
    }

    @Override
    public Boolean expire(String key, long timeout, TimeUnit unit) {
        Boolean result = redisTemplate.expire(key, timeout, unit);
        log.debug("设置哈希过期时间: {}，超时: {} {}，结果: {}", key, timeout, unit, result);
        return result;
    }

    @Override
    public Boolean expire(String key, Duration duration) {
        Boolean result = redisTemplate.expire(key, duration);
        log.debug("设置哈希过期时间: {}，持续时间: {}，结果: {}", key, duration, result);
        return result;
    }

    @Override
    public Long getExpire(String key, TimeUnit unit) {
        Long result = redisTemplate.getExpire(key, unit);
        log.debug("获取哈希过期时间: {}，单位: {}，结果: {}", key, unit, result);
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

    @Override
    public <T> Map<Object, T> scan(String key, String pattern, long count, Class<T> valueType) {
        ScanOptions scanOptions = ScanOptions.scanOptions()
                .match(pattern)
                .count(count)
                .build();

        Map<Object, T> result = new HashMap<>();
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash().scan(key, scanOptions)) {
            while (cursor.hasNext()) {
                Map.Entry<Object, Object> entry = cursor.next();
                T convertedValue = serializationHelper.convertValue(entry.getValue(), valueType);
                result.put(entry.getKey(), convertedValue);
            }
        }
        
        log.debug("扫描哈希字段: {}，模式: {}，数量: {}，类型: {}，结果数量: {}", 
                key, pattern, count, valueType.getSimpleName(), result.size());
        return result;
    }

    /**
     * 设置哈希字段值（支持Object类型和通配符Class类型）
     * 用于缓存场景，其中值类型可能是Object，类型是Class<?>
     */
    public void putWithWildcardType(String key, Object hashKey, Object value, Class<?> type) {
        redisTemplate.opsForHash().put(key, hashKey, value);
        log.debug("设置哈希字段: {}[{}]，类型: {}", key, hashKey, type.getSimpleName());
    }

    /**
     * 批量设置哈希字段值（支持Object类型和通配符Class类型）
     * 用于缓存场景，其中值类型可能是Object，类型是Class<?>
     */
    public void putAllWithWildcardType(String key, Map<Object, Object> map, Class<?> type) {
        if (map == null || map.isEmpty()) {
            return;
        }

        redisTemplate.opsForHash().putAll(key, map);
        log.debug("批量设置哈希字段: {}，字段数量: {}，类型: {}", key, map.size(), type.getSimpleName());
    }
}
