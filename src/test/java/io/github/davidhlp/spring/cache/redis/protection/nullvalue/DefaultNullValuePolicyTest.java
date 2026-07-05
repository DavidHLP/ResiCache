package io.github.davidhlp.spring.cache.redis.protection.nullvalue;

import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.support.NullValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DefaultNullValuePolicy 单元测试 — Round 35 (ADR-0048) 后:
 * 4 个纯方法 + 1 个 {@code toReturnValue} 委派测试。
 *
 * <p>{@code toReturnValue} 的字节生产细节由 {@link NullValueEncoderTest} 覆盖;
 * 本测试仅验证 {@code DefaultNullValuePolicy} 委派语义。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultNullValuePolicy Tests")
class DefaultNullValuePolicyTest {

    @Mock
    private NullValueEncoder encoder;

    @Mock
    private RedisCacheableOperation cacheOperation;

    private DefaultNullValuePolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DefaultNullValuePolicy(encoder);
    }

    @Nested
    @DisplayName("shouldCacheNull() Tests")
    class ShouldCacheNullTests {

        @Test
        @DisplayName("returns true when cacheOperation is not null and cacheNullValues is true")
        void shouldCacheNull_cacheNullValuesTrue_returnsTrue() {
            when(cacheOperation.isCacheNullValues()).thenReturn(true);

            boolean result = policy.shouldCacheNull(cacheOperation);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false when cacheNullValues is false")
        void shouldCacheNull_cacheNullValuesFalse_returnsFalse() {
            when(cacheOperation.isCacheNullValues()).thenReturn(false);

            boolean result = policy.shouldCacheNull(cacheOperation);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when cacheOperation is null")
        void shouldCacheNull_nullCacheOperation_returnsFalse() {
            boolean result = policy.shouldCacheNull(null);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("toStoreValue() Tests")
    class ToStoreValueTests {

        @Test
        @DisplayName("returns null when value is null and should cache null")
        void toStoreValue_nullValueAndCacheable_returnsNull() {
            when(cacheOperation.isCacheNullValues()).thenReturn(true);

            Object result = policy.toStoreValue(null, cacheOperation);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns null when value is null and should not cache null")
        void toStoreValue_nullValueAndNotCacheable_returnsNull() {
            when(cacheOperation.isCacheNullValues()).thenReturn(false);

            Object result = policy.toStoreValue(null, cacheOperation);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns original value when non-null")
        void toStoreValue_nonNullValue_returnsOriginal() {
            Object value = "test-value";

            Object result = policy.toStoreValue(value, cacheOperation);

            assertThat(result).isEqualTo(value);
        }

        @Test
        @DisplayName("returns original value when cacheOperation is null")
        void toStoreValue_nullCacheOperation_returnsOriginal() {
            Object value = "test-value";

            Object result = policy.toStoreValue(value, null);

            assertThat(result).isEqualTo(value);
        }
    }

    @Nested
    @DisplayName("fromStoreValue() Tests")
    class FromStoreValueTests {

        @Test
        @DisplayName("returns same value as input")
        void fromStoreValue_returnsSameValue() {
            Object storeValue = "stored-value";

            Object result = policy.fromStoreValue(storeValue);

            assertThat(result).isEqualTo(storeValue);
        }

        @Test
        @DisplayName("returns null when input is null")
        void fromStoreValue_nullInput_returnsNull() {
            Object result = policy.fromStoreValue(null);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("isNullValue() Tests")
    class IsNullValueTests {

        @Test
        @DisplayName("returns true for null value")
        void isNullValue_null_returnsTrue() {
            boolean result = policy.isNullValue(null);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false for non-null value")
        void isNullValue_nonNull_returnsFalse() {
            boolean result = policy.isNullValue("value");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false for NullValue instance")
        void isNullValue_nullValueInstance_returnsFalse() {
            // Note: This tests that NullValue.INSTANCE is not treated specially by isNullValue
            // The method checks value == null, not value instanceof NullValue
            boolean result = policy.isNullValue(NullValue.INSTANCE);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("toReturnValue() Tests — Round 35:delegates to NullValueEncoder")
    class ToReturnValueTests {

        @Test
        @DisplayName("forwards null value to encoder and returns encoded bytes")
        void toReturnValue_nullValue_delegatesToEncoder() {
            byte[] expectedBytes = new byte[]{1, 2, 3};
            when(encoder.encodeForReturn(eq(null), eq("test-cache"), eq("key")))
                    .thenReturn(expectedBytes);

            byte[] result = policy.toReturnValue(null, "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(encoder).encodeForReturn(null, "test-cache", "key");
        }

        @Test
        @DisplayName("forwards NullValue.INSTANCE to encoder and returns encoded bytes")
        void toReturnValue_nullValueInstance_delegatesToEncoder() {
            byte[] expectedBytes = new byte[]{1, 2, 3};
            when(encoder.encodeForReturn(eq(NullValue.INSTANCE), eq("test-cache"), eq("key")))
                    .thenReturn(expectedBytes);

            byte[] result = policy.toReturnValue(NullValue.INSTANCE, "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(encoder).encodeForReturn(NullValue.INSTANCE, "test-cache", "key");
        }

        @Test
        @DisplayName("forwards non-null value to encoder and returns encoded bytes")
        void toReturnValue_nonNull_delegatesToEncoder() {
            Object value = "test-value";
            byte[] expectedBytes = new byte[]{4, 5, 6};
            when(encoder.encodeForReturn(eq(value), eq("test-cache"), eq("key")))
                    .thenReturn(expectedBytes);

            byte[] result = policy.toReturnValue(value, "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(encoder).encodeForReturn(value, "test-cache", "key");
        }

        @Test
        @DisplayName("forwards arbitrary values to encoder with all three args")
        void toReturnValue_arbitraryValue_delegatesToEncoder() {
            byte[] expectedBytes = new byte[]{9, 9, 9};
            when(encoder.encodeForReturn(any(), any(), any())).thenReturn(expectedBytes);

            byte[] result = policy.toReturnValue("x", "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(encoder).encodeForReturn("x", "test-cache", "key");
        }
    }
}
