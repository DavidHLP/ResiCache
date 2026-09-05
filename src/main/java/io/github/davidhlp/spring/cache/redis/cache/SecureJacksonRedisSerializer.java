package io.github.davidhlp.spring.cache.redis.cache;





import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator.TypeMatcher;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.davidhlp.spring.cache.redis.serialization.SerializationException.EnvelopeCodec;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

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
class SecureJacksonRedisSerializer implements RedisSerializer<Object> {

    private final ObjectMapper objectMapper;
    private final boolean failOnUnknownType;
    private final WhitelistPolicy whitelistPolicy;
    private final String typeProperty;
    private final boolean polymorphicTypingEnabled;

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
        this.typeProperty = typeProperty == null || typeProperty.isBlank() ? "@class" : typeProperty;
        this.polymorphicTypingEnabled = polymorphicTypingEnabled;
        this.objectMapper = createSecureObjectMapper(
                objectMapper, this.whitelistPolicy, this.typeProperty, polymorphicTypingEnabled);
        this.failOnUnknownType = failOnUnknownType;
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
        // Field-level @JsonTypeInfo does not use the default-typing switch, so install the
        // same validator unconditionally as a second runtime guard. The streaming preflight
        // below remains necessary because wrapper-array ids can be presented before Jackson
        // resolves the annotated payload type.
        secureObjectMapper.setPolymorphicTypeValidator(typeValidator);

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
            Object envelope = EnvelopeCodec.create(value);
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
            // 单遍流式反序列化:用 JsonParser 一次性走完 bytes 验证
            // 所有 typeProperty 字段(走白名单),再收口为 VersionEnvelope。
            // 对大 payload(>10KB)避免完整 JsonNode 树构建 + 二次遍历,
            // ~30-40% CPU 节省、显著降低 GC 压力(transient JsonNode 消失)。
            try (com.fasterxml.jackson.core.JsonParser parser = objectMapper.createParser(bytes)) {
                validateTypeIdsStreaming(parser);
                // 流式 parser 不支持 rewind,验证后需重新 open 一个 parser 反序列化。
            }

            Object envelope;
            try (com.fasterxml.jackson.core.JsonParser parser = objectMapper.createParser(bytes)) {
                envelope = EnvelopeCodec.read(objectMapper, bytes);
            }

            if (EnvelopeCodec.version(envelope) != EnvelopeCodec.currentVersion()) {
                String message = String.format(
                        "Unsupported version envelope: expected=%d, actual=%d",
                        EnvelopeCodec.currentVersion(), EnvelopeCodec.version(envelope));
                if (failOnUnknownType) {
                    throw new SerializationException(message);
                }
                log.warn("{} — returning null", message);
                return null;
            }

            return EnvelopeCodec.payload(envelope);
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
     * 流式遍历 JSON,验证所有配置类型字段值和字段级/数组包装类型标识在白名单中。失败 fail-fast
     * 抛 {@link SerializationException};不构建中间 JsonNode 树,对大 payload
     * 显著降低内存与 GC。
     *
     * <p>遍历语义:任何 {@code FIELD_NAME} 与 {@code "@class"} 或配置的
     * {@code typeProperty} 匹配时,下一个 STRING token 视为 className。对
     * {@code VersionEnvelope.payload} / {@code CachedValue.value} 的 wrapper-array
     * 形式,数组首个 STRING token 同样视为 className。全局多态开启时,所有数组首项
     * 按同一规则检查。非匹配字段继续递归遍历。
     */
    private void validateTypeIdsStreaming(com.fasterxml.jackson.core.JsonParser parser)
            throws java.io.IOException {
        com.fasterxml.jackson.core.JsonToken token;
        java.util.Deque<ArrayFrame> arrays = new java.util.ArrayDeque<>();
        boolean expectingTypeValue = false;
        String currentFieldName = null;
        while ((token = parser.nextToken()) != null) {
            switch (token) {
                case START_OBJECT -> {
                    acceptParentArrayValue(arrays, true, null);
                    expectingTypeValue = false;
                }
                case END_OBJECT -> expectingTypeValue = false;
                case START_ARRAY -> {
                    acceptParentArrayValue(arrays, true, null);
                    arrays.push(new ArrayFrame(isPolymorphicContainer(currentFieldName)));
                    expectingTypeValue = false;
                }
                case END_ARRAY -> {
                    if (!arrays.isEmpty()) {
                        ArrayFrame frame = arrays.pop();
                        frame.validate(this::rejectIfDisallowed);
                    }
                    expectingTypeValue = false;
                }
                case FIELD_NAME -> {
                    currentFieldName = parser.currentName();
                    expectingTypeValue = isTypeProperty(currentFieldName);
                }
                case VALUE_STRING -> {
                    if (expectingTypeValue) {
                        String className = parser.getText();
                        rejectIfDisallowed(className);
                        expectingTypeValue = false;
                    }
                    acceptParentArrayValue(arrays, false, parser.getText());
                }
                default -> {
                    // 数值/布尔/null token:reset expectingTypeValue
                    expectingTypeValue = false;
                    acceptParentArrayValue(arrays, false, null);
                }
            }
        }
    }

    private boolean isTypeProperty(String fieldName) {
        return "@class".equals(fieldName) || typeProperty.equals(fieldName);
    }

    private boolean isPolymorphicContainer(String fieldName) {
        return polymorphicTypingEnabled || "payload".equals(fieldName) || "value".equals(fieldName);
    }

    private static void acceptParentArrayValue(
            java.util.Deque<ArrayFrame> arrays, boolean structured, String text) {
        if (!arrays.isEmpty()) {
            arrays.peek().accept(structured, text);
        }
    }

    private void rejectIfDisallowed(String className) {
        if (!isAllowedClass(className)) {
            throw new SerializationException(
                    "Type not in deserialization whitelist: " + className
                            + ". Add its package to resi-cache.serializer.allowed-package-prefixes.");
        }
    }

    private static final class ArrayFrame {
        private final boolean wrapperCandidate;
        private int elements;
        private String firstText;
        private boolean secondStructured;

        private ArrayFrame(boolean wrapperCandidate) {
            this.wrapperCandidate = wrapperCandidate;
        }

        private void accept(boolean structured, String text) {
            if (elements == 0) {
                firstText = text;
            } else if (elements == 1) {
                secondStructured = structured;
            }
            elements++;
        }

        private void validate(java.util.function.Consumer<String> reject) {
            // A normal business array is not a wrapper when it has more than two
            // elements. For the two-element shape, require a class-like first
            // token and a structured second value before treating it as a type id.
            if (wrapperCandidate && elements == 2 && firstText != null
                    && secondStructured && looksLikeTypeId(firstText)) {
                reject.accept(firstText);
            }
        }

        private static boolean looksLikeTypeId(String value) {
            return value.indexOf('.') > 0 || value.startsWith("[");
        }
    }

    private boolean isAllowedClass(String className) {
        // 委托 WhitelistPolicy 统一判断：前缀 + java.lang/java.time/java.math + ALLOWED_JAVA_UTIL_CLASSES 全集
        return whitelistPolicy.isClassNameAllowed(className);
    }
}
