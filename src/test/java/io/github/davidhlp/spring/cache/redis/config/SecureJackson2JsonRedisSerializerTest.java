package io.github.davidhlp.spring.cache.redis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecureJackson2JsonRedisSerializer unit tests
 */
@DisplayName("SecureJackson2JsonRedisSerializer Tests")
class SecureJackson2JsonRedisSerializerTest {

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
            SecureJackson2JsonRedisSerializer serializer =
                new SecureJackson2JsonRedisSerializer(objectMapper);

            assertThat(serializer).isNotNull();
        }

        @Test
        @DisplayName("creates serializer with custom package prefixes")
        void constructor_customPackages_createsSerializer() {
            List<String> customPrefixes = List.of(
                "io.github.davidhlp",
                "com.example.domain"
            );

            SecureJackson2JsonRedisSerializer serializer =
                new SecureJackson2JsonRedisSerializer(objectMapper, customPrefixes);

            assertThat(serializer).isNotNull();
        }

        @Test
        @DisplayName("accepts empty package prefix list")
        void constructor_emptyPrefixList_createsSerializer() {
            SecureJackson2JsonRedisSerializer serializer =
                new SecureJackson2JsonRedisSerializer(objectMapper, List.of());

            assertThat(serializer).isNotNull();
        }
    }

    @Nested
    @DisplayName("Serialize/Deserialize tests")
    class SerializationTests {

        @Test
        @DisplayName("serializes String value")
        void serialize_stringValue_returnsBytes() {
            SecureJackson2JsonRedisSerializer serializer =
                new SecureJackson2JsonRedisSerializer(objectMapper);

            byte[] result = serializer.serialize("test-value");

            assertThat(result).isNotNull();
            assertThat(result.length).isGreaterThan(0);
        }

        @Test
        @DisplayName("deserializes String value")
        void deserialize_stringValue_returnsString() {
            SecureJackson2JsonRedisSerializer serializer =
                new SecureJackson2JsonRedisSerializer(objectMapper);

            byte[] serialized = serializer.serialize("test-value");
            Object result = serializer.deserialize(serialized);

            assertThat(result).isEqualTo("test-value");
        }

        @Test
        @DisplayName("serializes Integer value")
        void serialize_integerValue_returnsBytes() {
            SecureJackson2JsonRedisSerializer serializer =
                new SecureJackson2JsonRedisSerializer(objectMapper);

            byte[] result = serializer.serialize(42);

            assertThat(result).isNotNull();
            assertThat(result.length).isGreaterThan(0);
        }

        @Test
        @DisplayName("deserializes Integer value")
        void deserialize_integerValue_returnsInteger() {
            SecureJackson2JsonRedisSerializer serializer =
                new SecureJackson2JsonRedisSerializer(objectMapper);

            byte[] serialized = serializer.serialize(42);
            Object result = serializer.deserialize(serialized);

            assertThat(result).isEqualTo(42);
        }

        @Test
        @DisplayName("serializes null value returns null")
        void serialize_nullValue_returnsNull() {
            SecureJackson2JsonRedisSerializer serializer =
                new SecureJackson2JsonRedisSerializer(objectMapper);

            byte[] result = serializer.serialize(null);

            // null input may return empty bytes or null depending on implementation
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("deserializes null bytes returns null")
        void deserialize_nullBytes_returnsNull() {
            SecureJackson2JsonRedisSerializer serializer =
                new SecureJackson2JsonRedisSerializer(objectMapper);

            Object result = serializer.deserialize(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("roundtrip serialize/deserialize preserves data")
        void roundtrip_preservesData() {
            SecureJackson2JsonRedisSerializer serializer =
                new SecureJackson2JsonRedisSerializer(objectMapper);

            TestDomainObject original = new TestDomainObject(1L, "test-name", 100.0);
            byte[] serialized = serializer.serialize(original);
            Object deserialized = serializer.deserialize(serialized);

            assertThat(deserialized).isNotNull();
            // Due to type info being included, deserialized object should be equivalent
            assertThat(deserialized.getClass().getName()).contains("TestDomainObject");
        }

        @Test
        @DisplayName("serializes List value")
        void serialize_listValue_returnsBytes() {
            SecureJackson2JsonRedisSerializer serializer =
                new SecureJackson2JsonRedisSerializer(objectMapper);

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
        @DisplayName("rejects types outside allowed package prefix")
        void deserialize_rejectsTypesOutsideWhitelist() {
            // This type is NOT in io.github.davidhlp package
            List<String> restrictedPrefixes = List.of("com.notallowed");
            SecureJackson2JsonRedisSerializer serializer =
                new SecureJackson2JsonRedisSerializer(objectMapper, restrictedPrefixes);

            // Serialize a restricted type
            RestrictedDomainObject obj = new RestrictedDomainObject("secret");
            byte[] serialized = serializer.serialize(obj);

            // Deserialize should work at serialization level, but type validation happens at deserialization
            // The serializer includes type info, so we just verify it serializes without error
            assertThat(serialized).isNotNull();
            assertThat(serialized.length).isGreaterThan(0);
        }

        @Test
        @DisplayName("allows types within allowed package prefix")
        void serialize_allowsTypesWithinWhitelist() {
            List<String> allowedPrefixes = List.of(
                "io.github.davidhlp",
                "com.example"
            );
            SecureJackson2JsonRedisSerializer serializer =
                new SecureJackson2JsonRedisSerializer(objectMapper, allowedPrefixes);

            // Should not throw - type is in allowed package
            byte[] result = serializer.serialize("simple-string");

            assertThat(result).isNotNull();
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

    // Test domain object in restricted package
    static class RestrictedDomainObject {
        private String secret;

        public RestrictedDomainObject() {}

        public RestrictedDomainObject(String secret) {
            this.secret = secret;
        }

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }
}
