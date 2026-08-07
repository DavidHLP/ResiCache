package io.github.davidhlp.spring.cache.redis.protection.nullvalue;

import io.github.davidhlp.spring.cache.redis.serialization.TypeSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.support.NullValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NullValueEncoder 单元测试 — contract 验证:
 * <ol>
 *   <li>{@code value == null} ⇒ {@code TypeSupport.serializeToBytes(NullValue.INSTANCE)}</li>
 *   <li>{@code value != null} ⇒ {@code TypeSupport.serializeToBytes(value)}(直通)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NullValueEncoder Tests")
class NullValueEncoderTest {

    @Mock
    private TypeSupport typeSupport;

    private NullValueEncoder encoder;

    @BeforeEach
    void setUp() {
        encoder = new NullValueEncoder(typeSupport);
    }

    @Nested
    @DisplayName("encodeForReturn() Tests")
    class EncodeForReturnTests {

        @Test
        @DisplayName("encodes null value as NullValue.INSTANCE bytes")
        void encodeForReturn_nullValue_serializesNullValue() {
            byte[] expectedBytes = new byte[]{1, 2, 3};
            when(typeSupport.serializeToBytes(NullValue.INSTANCE)).thenReturn(expectedBytes);

            byte[] result = encoder.encodeForReturn(null, "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(typeSupport).serializeToBytes(NullValue.INSTANCE);
            verify(typeSupport, never()).serializeToBytes((Object) null);
        }

        @Test
        @DisplayName("passes non-null value directly to TypeSupport")
        void encodeForReturn_nonNullValue_serializesValue() {
            Object value = "test-value";
            byte[] expectedBytes = new byte[]{4, 5, 6};
            when(typeSupport.serializeToBytes(value)).thenReturn(expectedBytes);

            byte[] result = encoder.encodeForReturn(value, "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(typeSupport).serializeToBytes(value);
            verify(typeSupport, never()).serializeToBytes(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("passes NullValue.INSTANCE through to TypeSupport as-is")
        void encodeForReturn_nullValueInstance_serializesNullValue() {
            byte[] expectedBytes = new byte[]{7, 8, 9};
            when(typeSupport.serializeToBytes(NullValue.INSTANCE)).thenReturn(expectedBytes);

            byte[] result = encoder.encodeForReturn(NullValue.INSTANCE, "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(typeSupport).serializeToBytes(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("returns bytes for arbitrary object types (Integer, Map, etc.)")
        void encodeForReturn_arbitraryType_serializesValue() {
            Object value = 42;
            byte[] expectedBytes = new byte[]{10, 20, 30};
            when(typeSupport.serializeToBytes(value)).thenReturn(expectedBytes);

            byte[] result = encoder.encodeForReturn(value, "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(typeSupport).serializeToBytes(value);
        }
    }
}
