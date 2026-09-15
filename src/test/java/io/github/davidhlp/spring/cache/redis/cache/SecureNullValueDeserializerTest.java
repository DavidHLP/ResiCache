package io.github.davidhlp.spring.cache.redis.cache;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.NullValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SecureNullValueDeserializer seam")
class SecureNullValueDeserializerTest {

    @Test
    @DisplayName("round-trips Spring NullValue through the allowed Java serialization format")
    void roundTripsNullValue() {
        byte[] serialized = SecureNullValueDeserializer.serializeNullValue();

        assertThat(SecureNullValueDeserializer.isJavaSerialized(serialized)).isTrue();
        assertThat(SecureNullValueDeserializer.deserializeNullValue(serialized))
                .isSameAs(NullValue.INSTANCE);
    }

    @Test
    @DisplayName("recognizes only the Java serialization stream prefix")
    void recognizesSerializationPrefix() {
        assertThat(SecureNullValueDeserializer.isJavaSerialized(null)).isFalse();
        assertThat(SecureNullValueDeserializer.isJavaSerialized(new byte[]{(byte) 0xAC, (byte) 0xED, 0x00}))
                .isFalse();
        assertThat(SecureNullValueDeserializer.isJavaSerialized(
                new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05})).isTrue();
        assertThat(SecureNullValueDeserializer.isJavaSerialized(
                new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x04})).isFalse();
    }

    @Test
    @DisplayName("rejects serialized classes outside the NullValue whitelist")
    void rejectsNonNullValueClass() throws Exception {
        byte[] serializedString = serialize("not-null");

        assertThatThrownBy(() -> SecureNullValueDeserializer.deserializeNullValue(serializedString))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("expected NullValue but got java.lang.String");
    }

    @Test
    @DisplayName("rejects malformed Java serialization input")
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> SecureNullValueDeserializer.deserializeNullValue(new byte[]{1, 2, 3}))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("only NullValue is permitted");
    }

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }
}
