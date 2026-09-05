package io.github.davidhlp.spring.cache.redis.cache;





import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationProperties;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LegacyValueDecoder")
class LegacyValueDecoderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LegacyValueDecoder decoder = new LegacyValueDecoder(
            objectMapper, List.of("io.github.davidhlp", "java.lang"), "@class");

    @Test
    void genericJackson_decodesAllowedValue() {
        var serializer = new GenericJackson2JsonRedisSerializer();
        byte[] bytes = serializer.serialize("legacy-json");

        assertThat(decoder.decode(bytes,
                SerializationMigrationProperties.LegacySerializer.GENERIC_JACKSON))
                .isEqualTo("legacy-json");
    }

    @Test
    void genericJackson_rejectsTypeIdOutsideWhitelistBeforeDeserialization() {
        byte[] bytes = "{\"@class\":\"com.attacker.Gadget\",\"value\":1}"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> decoder.decode(bytes,
                SerializationMigrationProperties.LegacySerializer.GENERIC_JACKSON))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("whitelist");
    }

    @Test
    void genericJackson_alwaysChecksLegacyClassPropertyWhenConfiguredPropertyDiffers() {
        LegacyValueDecoder customPropertyDecoder = new LegacyValueDecoder(
                objectMapper, List.of("io.github.davidhlp"), "_type");
        byte[] bytes = "{\"@class\":\"com.attacker.Gadget\",\"value\":1}"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> customPropertyDecoder.decode(bytes,
                SerializationMigrationProperties.LegacySerializer.GENERIC_JACKSON))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("whitelist");
    }

    @Test
    void jdk_decodesAllowedDomainValue() throws Exception {
        AllowedValue value = new AllowedValue("legacy-jdk");

        assertThat(decoder.decode(jdkBytes(value),
                SerializationMigrationProperties.LegacySerializer.JDK))
                .isEqualTo(value);
    }

    @Test
    void jdk_rejectsValueOutsideWhitelist() throws Exception {
        assertThatThrownBy(() -> decoder.decode(jdkBytes(new java.io.File("/tmp/x")),
                SerializationMigrationProperties.LegacySerializer.JDK))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("whitelist");
    }

    private byte[] jdkBytes(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private record AllowedValue(String value) implements Serializable {
    }
}
