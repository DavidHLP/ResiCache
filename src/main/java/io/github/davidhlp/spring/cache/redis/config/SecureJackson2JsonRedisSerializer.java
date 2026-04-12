package io.github.davidhlp.spring.cache.redis.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.List;

/**
 * Secure Jackson2 JSON Redis serializer that uses a whitelist-based PolymorphicTypeValidator.
 *
 * <p>This serializer addresses the deserialization vulnerability in the default
 * GenericJackson2JsonRedisSerializer by restricting type information to only allow
 * classes from configured package hierarchies.
 *
 * <p>Usage:
 * <pre>{@code
 * RedisTemplate<String, Object> template = new RedisTemplate<>();
 * template.setValueSerializer(new SecureJackson2JsonRedisSerializer(objectMapper));
 * }</pre>
 *
 * <p>Custom package prefixes can be configured via:
 * <pre>{@code
 * resi-cache:
 *   serializer:
 *     allowed-package-prefixes:
 *       - io.github.davidhlp
 *       - com.example.business
 *       - com.acme.domain
 * }</pre>
 *
 * @see PolymorphicTypeValidator
 * @see GenericJackson2JsonRedisSerializer
 */
public class SecureJackson2JsonRedisSerializer implements RedisSerializer<Object> {

    private static final String DEFAULT_ALLOWED_PACKAGE_PREFIX = "io.github.davidhlp";

    private final Jackson2JsonRedisSerializer<Object> delegate;

    /**
     * Creates a new SecureJackson2JsonRedisSerializer using the provided ObjectMapper
     * with default package prefix (io.github.davidhlp).
     *
     * @param objectMapper the ObjectMapper to use for JSON serialization/deserialization
     */
    public SecureJackson2JsonRedisSerializer(ObjectMapper objectMapper) {
        this(objectMapper, List.of(DEFAULT_ALLOWED_PACKAGE_PREFIX));
    }

    /**
     * Creates a new SecureJackson2JsonRedisSerializer using the provided ObjectMapper
     * with custom allowed package prefixes.
     *
     * @param objectMapper the ObjectMapper to use for JSON serialization/deserialization
     * @param allowedPackagePrefixes list of package prefixes to allow for deserialization
     */
    public SecureJackson2JsonRedisSerializer(ObjectMapper objectMapper, List<String> allowedPackagePrefixes) {
        this.delegate = createSecureSerializer(objectMapper, allowedPackagePrefixes);
    }

    private Jackson2JsonRedisSerializer<Object> createSecureSerializer(ObjectMapper objectMapper, List<String> allowedPackagePrefixes) {
        // Build type validator with allowed package prefixes
        BasicPolymorphicTypeValidator.Builder validatorBuilder =
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class);

        // Add each allowed package prefix
        for (String prefix : allowedPackagePrefixes) {
            validatorBuilder.allowIfBaseType(prefix);
        }

        PolymorphicTypeValidator typeValidator = validatorBuilder.build();

        // Clone the object mapper to avoid modifying the original
        ObjectMapper secureObjectMapper = objectMapper.copy();

        // Register JavaTimeModule if not already registered
        if (!secureObjectMapper.canSerialize(java.time.LocalDateTime.class)) {
            secureObjectMapper.registerModule(new JavaTimeModule());
        }

        // Enable type information for polymorphic deserialization
        secureObjectMapper.activateDefaultTyping(
                typeValidator,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        return new Jackson2JsonRedisSerializer<>(secureObjectMapper, Object.class);
    }

    @Override
    public byte[] serialize(Object value) {
        return delegate.serialize(value);
    }

    @Override
    public Object deserialize(byte[] bytes) {
        return delegate.deserialize(bytes);
    }
}
