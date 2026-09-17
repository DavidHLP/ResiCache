package io.github.davidhlp.spring.cache.redis.cache;




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
 * NullValueEncoder 单元测试 — null decision 与 value codec 的 contract。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NullValueEncoder Tests")
class NullValueEncoderTest {

    @Mock
    private CacheValueCodec valueCodec;

    private NullValueEncoder encoder;

    @BeforeEach
    void setUp() {
        encoder = new NullValueEncoder(valueCodec);
    }

    @Nested
    @DisplayName("encodeForReturn() Tests")
    class EncodeForReturnTests {

        @Test
        @DisplayName("encodes null value as NullValue.INSTANCE bytes")
        void encodeForReturn_nullValue_serializesNullValue() {
            byte[] expectedBytes = new byte[]{1, 2, 3};
            when(valueCodec.toValueBytes(NullValue.INSTANCE)).thenReturn(expectedBytes);

            byte[] result = encoder.encodeForReturn(null, "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(valueCodec).toValueBytes(NullValue.INSTANCE);
            verify(valueCodec, never()).toValueBytes((Object) null);
        }

        @Test
        @DisplayName("passes non-null value directly to CacheValueCodec")
        void encodeForReturn_nonNullValue_serializesValue() {
            Object value = "test-value";
            byte[] expectedBytes = new byte[]{4, 5, 6};
            when(valueCodec.toValueBytes(value)).thenReturn(expectedBytes);

            byte[] result = encoder.encodeForReturn(value, "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(valueCodec).toValueBytes(value);
            verify(valueCodec, never()).toValueBytes(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("passes NullValue.INSTANCE through to CacheValueCodec as-is")
        void encodeForReturn_nullValueInstance_serializesNullValue() {
            byte[] expectedBytes = new byte[]{7, 8, 9};
            when(valueCodec.toValueBytes(NullValue.INSTANCE)).thenReturn(expectedBytes);

            byte[] result = encoder.encodeForReturn(NullValue.INSTANCE, "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(valueCodec).toValueBytes(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("returns bytes for arbitrary object types (Integer, Map, etc.)")
        void encodeForReturn_arbitraryType_serializesValue() {
            Object value = 42;
            byte[] expectedBytes = new byte[]{10, 20, 30};
            when(valueCodec.toValueBytes(value)).thenReturn(expectedBytes);

            byte[] result = encoder.encodeForReturn(value, "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(valueCodec).toValueBytes(value);
        }
    }
}
