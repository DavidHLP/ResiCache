package io.github.davidhlp.spring.cache.redis.serialization;

import io.github.davidhlp.spring.cache.redis.cache.CachedValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.SerializationException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SecureJacksonRedisSerializer unit tests
 */
@DisplayName("SecureJacksonRedisSerializer Tests")
class SecureJacksonRedisSerializerTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("Constructor tests")
    class ConstructorTests {

        @Test
        @DisplayName("creates serializer with default package prefix")
        void constructor_defaultPackage_createsSerializer() {
            SecureJacksonRedisSerializer serializer =
                new SecureJacksonRedisSerializer(objectMapper);

            assertThat(serializer).isNotNull();
        }

        @Test
        @DisplayName("creates serializer with custom package prefixes")
        void constructor_customPackages_createsSerializer() {
            List<String> customPrefixes = List.of(
                "io.github.davidhlp",
                "com.example.domain"
            );

            SecureJacksonRedisSerializer serializer =
                new SecureJacksonRedisSerializer(objectMapper, customPrefixes);

            assertThat(serializer).isNotNull();
        }

        @Test
        @DisplayName("accepts empty package prefix list")
        void constructor_emptyPrefixList_createsSerializer() {
            SecureJacksonRedisSerializer serializer =
                new SecureJacksonRedisSerializer(objectMapper, List.of());

            assertThat(serializer).isNotNull();
        }
    }

    @Nested
    @DisplayName("Serialize/Deserialize tests")
    class SerializationTests {

        @Test
        @DisplayName("serializes String value")
        void serialize_stringValue_returnsBytes() {
            SecureJacksonRedisSerializer serializer =
                new SecureJacksonRedisSerializer(objectMapper);

            byte[] result = serializer.serialize("test-value");

            assertThat(result).isNotNull();
            assertThat(result.length).isGreaterThan(0);
        }

        @Test
        @DisplayName("deserializes String value")
        void deserialize_stringValue_returnsString() {
            SecureJacksonRedisSerializer serializer =
                new SecureJacksonRedisSerializer(objectMapper);

            byte[] serialized = serializer.serialize("test-value");
            Object result = serializer.deserialize(serialized);

            assertThat(result).isEqualTo("test-value");
        }

        @Test
        @DisplayName("serializes Integer value")
        void serialize_integerValue_returnsBytes() {
            SecureJacksonRedisSerializer serializer =
                new SecureJacksonRedisSerializer(objectMapper);

            byte[] result = serializer.serialize(42);

            assertThat(result).isNotNull();
            assertThat(result.length).isGreaterThan(0);
        }

        @Test
        @DisplayName("deserializes Integer value")
        void deserialize_integerValue_returnsInteger() {
            SecureJacksonRedisSerializer serializer =
                new SecureJacksonRedisSerializer(objectMapper);

            byte[] serialized = serializer.serialize(42);
            Object result = serializer.deserialize(serialized);

            assertThat(result).isEqualTo(42);
        }

        @Test
        @DisplayName("serializes null value returns empty bytes")
        void serialize_nullValue_returnsEmptyBytes() {
            SecureJacksonRedisSerializer serializer =
                new SecureJacksonRedisSerializer(objectMapper);

            // serialize(null) 返回 new byte[0](见源码),而非 null —— 名实需一致
            byte[] result = serializer.serialize(null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("deserializes null bytes returns null")
        void deserialize_nullBytes_returnsNull() {
            SecureJacksonRedisSerializer serializer =
                new SecureJacksonRedisSerializer(objectMapper);

            Object result = serializer.deserialize(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("roundtrip serialize/deserialize preserves data")
        void roundtrip_preservesData() {
            SecureJacksonRedisSerializer serializer =
                new SecureJacksonRedisSerializer(objectMapper);

            TestDomainObject original = new TestDomainObject(1L, "test-name", 100.0);
            byte[] serialized = serializer.serialize(original);
            Object deserialized = serializer.deserialize(serialized);

            // 验证类型 + 全部字段,而非仅类名(否则字段全 null 测试仍绿)
            assertThat(deserialized).isInstanceOf(TestDomainObject.class);
            TestDomainObject restored = (TestDomainObject) deserialized;
            assertThat(restored.getId()).isEqualTo(1L);
            assertThat(restored.getName()).isEqualTo("test-name");
            assertThat(restored.getValue()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("roundtrip CachedValue keeps class in whitelist and preserves payload value")
        void roundtrip_cachedValue_preservesPayloadValue() {
            // 铁律 #6：CachedValue(io.github.davidhlp...cache.CachedValue) 与 payload 必须在白名单
            // （默认前缀 io.github.davidhlp 覆盖）。本用例验证 CachedValue 经 envelope 序列化往返后：
            //   1) @class 类型标识在白名单内（不被 validateTypeIds 拒绝 → 不抛 SecurityException）
            //   2) payload value 字段往返保持
            //   3) 反序列化产物仍为 CachedValue 实例
            // 注意：ttl/version/createdTime 等字段因 CachedValue 的 getter 全 @JsonIgnore、
            // 默认 PUBLIC_ONLY 字段可见性下不进 JSON（这是 CachedValue 既有设计，与本次挪包无关）。
            SecureJacksonRedisSerializer serializer =
                new SecureJacksonRedisSerializer(objectMapper);

            CachedValue original = CachedValue.of("payload-value", 60L);
            byte[] serialized = serializer.serialize(original);
            Object deserialized = serializer.deserialize(serialized);

            assertThat(deserialized).isNotNull();
            assertThat(deserialized).isInstanceOf(CachedValue.class);
            CachedValue restored = (CachedValue) deserialized;
            assertThat(restored.getValue()).isEqualTo("payload-value");
        }

        @Test
        @DisplayName("serializes List value")
        void serialize_listValue_returnsBytes() {
            SecureJacksonRedisSerializer serializer =
                new SecureJacksonRedisSerializer(objectMapper);

            List<String> listValue = List.of("a", "b", "c");
            byte[] result = serializer.serialize(listValue);

            assertThat(result).isNotNull();
            assertThat(result.length).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Security whitelist tests")
    class SecurityTests {

        @Test
        @DisplayName("deserialize rejects @class types outside whitelist (gadget-chain defense)")
        void deserialize_rejectsTypesOutsideWhitelist() {
            // 白名单只允许 com.notallowed 前缀
            List<String> restrictedPrefixes = List.of("com.notallowed");
            SecureJacksonRedisSerializer serializer =
                new SecureJacksonRedisSerializer(objectMapper, restrictedPrefixes);

            // 手工构造恶意 envelope：payload 的 @class 指向白名单外的攻击者类型,
            // 模拟攻击者注入 gadget 链。VersionEnvelope.payload 的 @JsonTypeInfo(Id.CLASS)
            // 会写出 @class,而 deserialize 的 validateTypeIds() 在反序列化前递归校验所有
            // @class —— 非白名单类型在此被拒绝(防 RCE)。原测试仅 serialize 不 deserialize,
            // 从未走到 validateTypeIds,故即使删除白名单校验也通过(虚假安全感)。
            String maliciousJson = "{\"version\":2,\"payload\":{"
                + "\"@class\":\"com.attacker.MaliciousGadget\"}}";

            assertThatThrownBy(
                    () -> serializer.deserialize(maliciousJson.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("whitelist");
        }

        @Test
        @DisplayName("allows types within allowed package prefix (roundtrip preserves type)")
        void serialize_allowsTypesWithinWhitelist() {
            List<String> allowedPrefixes = List.of(
                "io.github.davidhlp",
                "com.example"
            );
            SecureJacksonRedisSerializer serializer =
                new SecureJacksonRedisSerializer(objectMapper, allowedPrefixes);

            // 白名单内类型应能完整往返(类型 + 字段),而非仅"序列化不抛异常"
            TestDomainObject original = new TestDomainObject(7L, "allowed", 50.0);
            byte[] serialized = serializer.serialize(original);
            Object deserialized = serializer.deserialize(serialized);

            assertThat(deserialized).isInstanceOf(TestDomainObject.class);
            assertThat(((TestDomainObject) deserialized).getId()).isEqualTo(7L);
        }
    }

    // Test domain objects in allowed package
    static class TestDomainObject {
        private Long id;
        private String name;
        private Double value;

        public TestDomainObject() {}

        public TestDomainObject(Long id, String name, Double value) {
            this.id = id;
            this.name = name;
            this.value = value;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Double getValue() { return value; }
        public void setValue(Double value) { this.value = value; }
    }
}
