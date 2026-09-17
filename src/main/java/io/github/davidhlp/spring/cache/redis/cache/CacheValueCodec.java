package io.github.davidhlp.spring.cache.redis.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.serialization.SerializationException;
import org.springframework.cache.support.NullValue;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 缓存 value 字节契约的唯一内部 owner。
 *
 * <p>缓存配置中的 {@link SecureJacksonRedisSerializer} 负责产生 value 字节；本类在 writer seam
 * 消费并产生同一 plain-JSON 字节表示。写入值面向 chain 的表示是 envelope-shaped JSON
 * document，即包含 {@code version} 与 {@code payload} 的 {@code Map}，并保留 payload 中的
 * {@code @class} 类型字段。{@link NullValue} 不走 JSON，而使用受限 Java 序列化。
 *
 * <p>因此，若替换缓存 value serializer，替换方必须同时提供兼容这一契约的 codec。该行为由
 * {@code CacheValueCodecRoundTripIntegrationTest}（T0 gate）作为 characterization baseline。
 */
@Component
class CacheValueCodec {

    private final ObjectMapper objectMapper;

    public CacheValueCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将 writer seam 收到的 value 字节还原为 chain-facing value。
     *
     * @param valueBytes value 字节，空数组表示缓存未提供值
     * @return chain-facing value；空数组返回 {@code null}
     * @throws SerializationException JSON 字节无法读取
     * @throws SecurityException Java 序列化字节不是受限的 {@link NullValue}
     */
    @Nullable
    public Object fromValueBytes(@NonNull byte[] valueBytes) {
        if (valueBytes.length == 0) {
            return null;
        }
        if (SecureNullValueDeserializer.isJavaSerialized(valueBytes)) {
            return SecureNullValueDeserializer.deserializeNullValue(valueBytes);
        }
        try {
            return objectMapper.readValue(valueBytes, Object.class);
        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize value", e);
        }
    }

    /**
     * 将 chain-facing value 写回 writer seam 所需的 value 字节。
     *
     * @param chainValue chain-facing value；{@link NullValue} 使用受限 Java 序列化，其他值使用 JSON
     * @return plain-JSON 或受限 Java 序列化后的 value 字节
     * @throws SerializationException value 无法写出为 JSON
     */
    @NonNull
    public byte[] toValueBytes(@Nullable Object chainValue) {
        if (chainValue instanceof NullValue) {
            return SecureNullValueDeserializer.serializeNullValue();
        }
        try {
            return objectMapper.writeValueAsBytes(chainValue);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Failed to serialize value", e);
        }
    }
}
