package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.nullvalue;

import io.github.davidhlp.spring.cache.redis.core.writer.support.type.TypeSupport;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DefaultNullValuePolicy 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultNullValuePolicy Tests")
class DefaultNullValuePolicyTest {

    @Mock
    private TypeSupport typeSupport;

    @Mock
    private RedisCacheableOperation cacheOperation;

    private DefaultNullValuePolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DefaultNullValuePolicy(typeSupport);
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
    @DisplayName("toReturnValue() Tests")
    class ToReturnValueTests {

        @Test
        @DisplayName("returns serialized NullValue when value is null")
        void toReturnValue_nullValue_serializesNullValue() {
            byte[] expectedBytes = new byte[]{1, 2, 3};
            when(typeSupport.serializeToBytes(NullValue.INSTANCE)).thenReturn(expectedBytes);

            byte[] result = policy.toReturnValue(null, "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(typeSupport).serializeToBytes(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("returns serialized NullValue when value is NullValue")
        void toReturnValue_nullValueInstance_serializesNullValue() {
            byte[] expectedBytes = new byte[]{1, 2, 3};
            when(typeSupport.serializeToBytes(NullValue.INSTANCE)).thenReturn(expectedBytes);

            byte[] result = policy.toReturnValue(NullValue.INSTANCE, "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(typeSupport).serializeToBytes(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("returns serialized value when value is non-null")
        void toReturnValue_nonNull_serializesValue() {
            Object value = "test-value";
            byte[] expectedBytes = new byte[]{4, 5, 6};
            when(typeSupport.serializeToBytes(value)).thenReturn(expectedBytes);

            byte[] result = policy.toReturnValue(value, "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(typeSupport).serializeToBytes(value);
            verify(typeSupport, never()).serializeToBytes(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("returns serialized NullValue when value is null regardless of cacheOperation")
        void toReturnValue_nullValueAndNullCacheOperation_serializesNullValue() {
            byte[] expectedBytes = new byte[]{1, 2, 3};
            when(typeSupport.serializeToBytes(NullValue.INSTANCE)).thenReturn(expectedBytes);

            byte[] result = policy.toReturnValue(null, "test-cache", "key");

            assertThat(result).isEqualTo(expectedBytes);
            verify(typeSupport).serializeToBytes(NullValue.INSTANCE);
        }
    }
}
