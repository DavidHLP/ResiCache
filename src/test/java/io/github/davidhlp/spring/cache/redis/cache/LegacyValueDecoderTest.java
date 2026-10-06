package io.github.davidhlp.spring.cache.redis.cache;





import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationProperties;
import java.io.ByteArrayOutputStream;
import java.io.InvalidClassException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LegacyValueDecoder")
@SuppressWarnings("removal")
class LegacyValueDecoderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LegacyValueDecoder decoder = new LegacyValueDecoder(
            objectMapper, List.of("io.github.davidhlp", "java.lang"), "@class");

    @Test
    void genericJackson_decodesAllowedValue() {
        // Intentional: produces payloads in the legacy Jackson-2 format that the
        // decoder must read; the Jackson-3 replacement cannot emit it.
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

    @Test
    void jdk_rejectsHugeDeclaredArrayBeforeAllocation() throws Exception {
        byte[] bytes = jdkBytes(new byte[0]);
        // Only the declared length changes; there is no giant payload or allocation in this test.
        ByteBuffer.wrap(bytes).putInt(bytes.length - Integer.BYTES, Integer.MAX_VALUE);

        assertThatThrownBy(() -> decoder.decode(bytes,
                SerializationMigrationProperties.LegacySerializer.JDK))
                .isInstanceOf(SerializationException.class)
                .hasRootCauseInstanceOf(InvalidClassException.class)
                .hasStackTraceContaining("filter status: REJECTED");
    }

    @Test
    void jdk_rejectsDeepObjectGraph() throws Exception {
        List<Object> value = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            List<Object> parent = new ArrayList<>();
            parent.add(value);
            value = parent;
        }
        byte[] bytes = jdkBytes(value);

        assertThatThrownBy(() -> decoder.decode(bytes,
                SerializationMigrationProperties.LegacySerializer.JDK))
                .isInstanceOf(SerializationException.class)
                .hasRootCauseInstanceOf(InvalidClassException.class)
                .hasStackTraceContaining("filter status: REJECTED");
    }

    @Test
    void jdk_rejectsExcessiveReferences() throws Exception {
        List<Object> value = new ArrayList<>();
        AllowedValue repeated = new AllowedValue("repeated");
        for (int i = 0; i < 100_001; i++) {
            value.add(repeated);
        }
        byte[] bytes = jdkBytes(value);

        assertThatThrownBy(() -> decoder.decode(bytes,
                SerializationMigrationProperties.LegacySerializer.JDK))
                .isInstanceOf(SerializationException.class)
                .hasRootCauseInstanceOf(InvalidClassException.class)
                .hasStackTraceContaining("filter status: REJECTED");
    }

    @Test
    void jdk_rejectsOversizedConcreteString() throws Exception {
        byte[] bytes = jdkBytes("x".repeat(16 * 1024 * 1024));

        assertThatThrownBy(() -> decoder.decode(bytes,
                SerializationMigrationProperties.LegacySerializer.JDK))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("16 MiB input limit");
    }

    @Test
    void jdk_decodesBoundedNestedArrayValue() throws Exception {
        List<Object> value = new ArrayList<>();
        value.add(new byte[] {1, 2, 3});
        value.add(new AllowedValue[] {new AllowedValue("nested")});

        Object restored = decoder.decode(jdkBytes(value),
                SerializationMigrationProperties.LegacySerializer.JDK);

        assertThat(restored).isInstanceOf(List.class);
        List<?> values = (List<?>) restored;
        assertThat((byte[]) values.get(0)).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat((AllowedValue[]) values.get(1)).containsExactly(new AllowedValue("nested"));
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
