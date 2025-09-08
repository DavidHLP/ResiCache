package com.david.spring.cache.redis.serialization;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

/**
 * Redis 序列化辅助工具类
 *
 * <p>提供类型安全的序列化和反序列化功能
 *
 * @author David
 */
@Getter
@Slf4j
public class RedisSerialization {

    /** -- GETTER -- 获取底层的 ObjectMapper */
    private final ObjectMapper objectMapper;

    /** -- GETTER -- 获取底层的序列化器 */
    private final GenericJackson2JsonRedisSerializer serializer;

    public RedisSerialization() {
        this.objectMapper = new ObjectMapper();
        this.serializer = new GenericJackson2JsonRedisSerializer(objectMapper);
    }

    /**
     * 序列化对象
     *
     * @param value 待序列化的对象
     * @param <T> 对象类型
     * @return 序列化后的字节数组
     */
    public <T> byte[] serialize(T value) {
        if (value == null) {
            return null;
        }
        return serializer.serialize(value);
    }

    /**
     * 反序列化对象
     *
     * @param data 序列化的字节数组
     * @param type 目标类型
     * @param <T> 目标类型泛型
     * @return 反序列化后的对象，失败返回 null
     */
    public <T> T deserialize(byte[] data, Class<T> type) {
        if (data == null || data.length == 0) {
            return null;
        }

        try {
            JavaType javaType = objectMapper.getTypeFactory().constructType(type);
            return objectMapper.readValue(data, javaType);
        } catch (Exception e) {
            log.warn("反序列化数据到类型 {} 失败", type.getSimpleName(), e);
            return null;
        }
    }

    /**
     * 类型转换
     *
     * @param value 源对象
     * @param type 目标类型
     * @param <T> 目标类型泛型
     * @return 转换后的对象，失败返回 null
     */
    public <T> T convertValue(Object value, Class<T> type) {
        if (value == null) {
            return null;
        }

        try {
            // 如果结果已经是期望的类型，直接返回
            if (type.isInstance(value)) {
                return type.cast(value);
            }

            // 如果是字节数组，进行反序列化
            if (value instanceof byte[]) {
                return deserialize((byte[]) value, type);
            }

            // 使用 ObjectMapper 进行类型转换
            return objectMapper.convertValue(value, type);
        } catch (Exception e) {
            log.warn("转换值到类型 {} 失败", type.getSimpleName(), e);
            return null;
        }
    }

    /**
     * 类型安全的转换
     *
     * @param value 源对象
     * @param type 目标类型
     * @param <T> 目标类型泛型
     * @return 转换后的对象，失败抛出异常
     * @throws IllegalArgumentException 转换失败时抛出
     */
    public <T> T convertValueStrict(Object value, Class<T> type) {
        T result = convertValue(value, type);
        if (result == null && value != null) {
            throw new IllegalArgumentException("无法将值转换为类型: " + type.getSimpleName());
        }
        return result;
    }
}
