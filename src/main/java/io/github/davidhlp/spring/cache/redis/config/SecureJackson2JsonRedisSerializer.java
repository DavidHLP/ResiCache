package io.github.davidhlp.spring.cache.redis.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator.TypeMatcher;
import com.fasterxml.jackson.databind.jsontype.impl.ClassNameIdResolver;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
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
 * <h2>Why Package Whitelist?</h2>
 * <p>Without whitelist validation, Jackson's default typing can deserialize arbitrary classes
 * from the classpath, enabling remote code execution (RCE) attacks if Redis is compromised.
 * This serializer restricts deserialization to explicitly allowed package prefixes only.
 *
 * <h2>Configuration</h2>
 * <p>Default allowed package: {@code io.github.davidhlp}
 * <p>To add custom packages, configure in application.yml:
 * <pre>{@code
 * # application.yml
 * spring:
 *   data:
 *     redis:
 *       host: localhost
 *       port: 6379
 *
 * resi-cache:
 *   serializer:
 *     allowed-package-prefixes:
 *       - io.github.davidhlp        # default, required for ResiCache internals
 *       - com.example.business      # your domain objects (add this)
 *       - com.acme.domain           # another package (add as needed)
 *       - com.example.dto           # DTOs and value objects (add as needed)
 * }</pre>
 *
 * <h2>Full Spring Boot 3.x Configuration Example</h2>
 * <pre>{@code
 * # application.yml - Complete ResiCache Configuration
 * spring:
 *   application:
 *     name: my-application
 *   data:
 *     redis:
 *       host: ${REDIS_HOST:localhost}
 *       port: ${REDIS_PORT:6379}
 *       password: ${REDIS_PASSWORD:}
 *       timeout: 5000ms
 *
 * resi-cache:
 *   default-ttl: 30m
 *   serializer:
 *     allowed-package-prefixes:
 *       - io.github.davidhlp        # REQUIRED: ResiCache internal classes
 *       - com.example.business       # Your business domain objects
 *       - com.example.dto            # Data transfer objects
 *       - com.acme.domain           # Additional domain packages
 *   bloom-filter:
 *     enabled: true
 *     expected-insertions: 100000
 *     false-probability: 0.01
 *   lock:
 *     enabled: true
 *     wait-time: 5
 *     lease-time: 30
 *   pre-refresh:
 *     enabled: true
 *     threshold: 0.8
 *     core-pool-size: 4
 * }</pre>
 *
 * <h2>Common Pitfalls</h2>
 * <ul>
 *   <li>If your cached objects are in a package <strong>not</strong> in the whitelist,
 *       deserialization will fail silently and return {@code null}.</li>
 *   <li>Ensure all cached domain object packages are listed before first deployment.</li>
 * </ul>
 *
 * @see PolymorphicTypeValidator
 * @see BasicPolymorphicTypeValidator
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
        // Build a secure type validator with explicit package whitelist using TypeMatcher
        // BasicPolymorphicTypeValidator.Builder provides production-safe validation
        String[] prefixes = allowedPackagePrefixes.toArray(new String[0]);
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(new TypeMatcher() {
                    @Override
                    public boolean match(MapperConfig<?> config, Class<?> rawSubType) {
                        if (rawSubType == null) {
                            return false;
                        }
                        String className = rawSubType.getName();
                        for (String prefix : prefixes) {
                            if (className.startsWith(prefix)) {
                                return true;
                            }
                        }
                        return false;
                    }
                })
                .allowIfSubType(Object.class)
                .build();

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
