package io.github.davidhlp.spring.cache.redis.serialization;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator.TypeMatcher;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.util.List;

/**
 * Secure Jackson2 JSON Redis serializer that uses a whitelist-based PolymorphicTypeValidator.
 *
 * <p>Security improvements over default GenericJackson2JsonRedisSerializer:
 * <ul>
 *   <li>Polymorphic typing is <strong>disabled by default</strong> — only enabled when explicitly configured</li>
 *   <li>When enabled, type information is restricted to configured package whitelist only</li>
 *   <li>All cached values are wrapped in a {@link VersionEnvelope} for version control and safe migration</li>
 * </ul>
 *
 * <p>序列化格式：
 * <pre>{@code
 * {
 *   "version": 2,
 *   "payload": { ...actual cached value... }
 * }
 * }</pre>
 */
@Slf4j
public class SecureJacksonRedisSerializer implements RedisSerializer<Object> {

    private final ObjectMapper objectMapper;
    private final boolean failOnUnknownType;
    private final List<String> allowedPackagePrefixes;
    private final WhitelistPolicy whitelistPolicy;

    /**
     * Creates a new SecureJacksonRedisSerializer using the provided ObjectMapper
     * with default package prefix (io.github.davidhlp).
     *
     * @param objectMapper the ObjectMapper to use for JSON serialization/deserialization
     */
    public SecureJacksonRedisSerializer(ObjectMapper objectMapper) {
        this(objectMapper, List.of(WhitelistPolicy.DEFAULT_ALLOWED_PACKAGE_PREFIX), true, "@class", false);
    }

    /**
     * Creates a new SecureJacksonRedisSerializer using the provided ObjectMapper
     * with custom allowed package prefixes.
     *
     * @param objectMapper the ObjectMapper to use for JSON serialization/deserialization
     * @param allowedPackagePrefixes list of package prefixes to allow for deserialization
     */
    public SecureJacksonRedisSerializer(ObjectMapper objectMapper, List<String> allowedPackagePrefixes) {
        this(objectMapper, allowedPackagePrefixes, true, "@class", false);
    }

    /**
     * Creates a new SecureJacksonRedisSerializer with full configuration.
     *
     * @param objectMapper the ObjectMapper to use for JSON serialization/deserialization
     * @param allowedPackagePrefixes list of package prefixes to allow for deserialization
     * @param failOnUnknownType whether to fail on unknown types during deserialization
     * @param typeProperty the Jackson type property name (e.g. "@class")
     * @param polymorphicTypingEnabled whether to enable Jackson polymorphic typing
     */
    public SecureJacksonRedisSerializer(ObjectMapper objectMapper,
                                             List<String> allowedPackagePrefixes,
                                             boolean failOnUnknownType,
                                             String typeProperty,
                                             boolean polymorphicTypingEnabled) {
        this.whitelistPolicy = new WhitelistPolicy(allowedPackagePrefixes);
        this.objectMapper = createSecureObjectMapper(objectMapper, this.whitelistPolicy, typeProperty, polymorphicTypingEnabled);
        this.failOnUnknownType = failOnUnknownType;
        this.allowedPackagePrefixes = List.copyOf(allowedPackagePrefixes);
    }

    private ObjectMapper createSecureObjectMapper(ObjectMapper objectMapper,
                                                  WhitelistPolicy whitelistPolicy,
                                                  String typeProperty,
                                                  boolean polymorphicTypingEnabled) {
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(new TypeMatcher() {
                    @Override
                    public boolean match(MapperConfig<?> config, Class<?> rawSubType) {
                        // 委托 WhitelistPolicy 统一判断（前缀 + java.lang/java.time/java.math/java.util 集合）
                        return whitelistPolicy.isClassAllowed(rawSubType);
                    }
                })
                .build();

        ObjectMapper secureObjectMapper = objectMapper.copy();

        if (!secureObjectMapper.canSerialize(java.time.LocalDateTime.class)) {
            secureObjectMapper.registerModule(new JavaTimeModule());
        }

        if (polymorphicTypingEnabled) {
            // 使用自定义 type property 启用多态类型信息
            ObjectMapper.DefaultTypeResolverBuilder typer = new ObjectMapper.DefaultTypeResolverBuilder(
                    ObjectMapper.DefaultTyping.NON_FINAL);
            typer.init(JsonTypeInfo.Id.CLASS, null);
            typer.inclusion(JsonTypeInfo.As.PROPERTY);
            typer.typeProperty(typeProperty);
            secureObjectMapper.setDefaultTyping(typer);
            log.info("Polymorphic typing enabled with typeProperty='{}' and package whitelist", typeProperty);
        } else {
            log.debug("Polymorphic typing disabled (default secure mode)");
        }

        return secureObjectMapper;
    }

    @Override
    public byte[] serialize(Object value) {
        if (value == null) {
            return new byte[0];
        }
        try {
            VersionEnvelope envelope = new VersionEnvelope(VersionEnvelope.CURRENT_VERSION, value);
            return objectMapper.writeValueAsBytes(envelope);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Could not serialize value: " + e.getMessage(), e);
        }
    }

    @Override
    public Object deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        // NullValue 因 Spring 设计（final + 私有构造）只能用 Java 序列化往返、无法 JSON 化。
        // 缓存命中 null 值时上游会产出 NullValue 的 Java 序列化字节，此处识别并用受限白名单
        // （仅允许 NullValue）安全还原，使 Spring RedisCache.lookup 能正确返回 NullValue。
        if (SecureNullValueDeserializer.isJavaSerialized(bytes)) {
            return SecureNullValueDeserializer.deserializeNullValue(bytes);
        }
        try {
            // 先解析为 JsonNode，递归验证所有 @class 属性在白名单中
            // 这能阻止 @JsonTypeInfo 注解绕过 BasicPolymorphicTypeValidator
            JsonNode rootNode = objectMapper.readTree(bytes);
            validateTypeIds(rootNode);

            VersionEnvelope envelope = objectMapper.treeToValue(rootNode, VersionEnvelope.class);

            if (envelope.getVersion() != VersionEnvelope.CURRENT_VERSION) {
                String message = String.format(
                        "Unsupported version envelope: expected=%d, actual=%d",
                        VersionEnvelope.CURRENT_VERSION, envelope.getVersion());
                if (failOnUnknownType) {
                    throw new SerializationException(message);
                }
                log.warn("{} — returning null", message);
                return null;
            }

            return envelope.getPayload();
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            if (failOnUnknownType) {
                throw new SerializationException("Could not deserialize value: " + e.getMessage(), e);
            }
            log.warn("Deserialization failed (failOnUnknownType=false, returning null): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 递归验证 JsonNode 中所有 {@code @class} 类型标识符是否在白名单中。
     *
     * <p>由于 {@code @JsonTypeInfo(use = Id.CLASS)} 会绕过
     * {@code BasicPolymorphicTypeValidator}，我们在反序列化前手动做二次校验。
     */
    private void validateTypeIds(JsonNode node) {
        if (node.isObject()) {
            JsonNode classNode = node.get("@class");
            if (classNode != null && classNode.isTextual()) {
                String className = classNode.asText();
                if (!isAllowedClass(className)) {
                    throw new SerializationException(
                            "Type not in deserialization whitelist: " + className);
                }
            }
            node.fields().forEachRemaining(entry -> validateTypeIds(entry.getValue()));
        } else if (node.isArray()) {
            node.forEach(this::validateTypeIds);
        }
    }

    private boolean isAllowedClass(String className) {
        // 委托 WhitelistPolicy 统一判断：前缀 + java.lang/java.time/java.math + ALLOWED_JAVA_UTIL_CLASSES 全集
        return whitelistPolicy.isClassNameAllowed(className);
    }
}
