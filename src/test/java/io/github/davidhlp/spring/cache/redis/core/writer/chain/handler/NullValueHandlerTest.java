package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.nullvalue.NullValuePolicy;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NullValueHandler 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NullValueHandler Tests")
class NullValueHandlerTest {

    @Mock
    private NullValuePolicy nullValuePolicy;

    @Mock
    private RedisCacheableOperation cacheOperation;

    private NullValueHandler handler;

    @BeforeEach
    void setUp() {
        handler = new NullValueHandler(nullValuePolicy);
    }

    private CacheContext createContext(CacheOperation operation, Object deserializedValue) {
        CacheInput input = new CacheInput(
                operation,
                "test-cache",
                "test:key",
                "key",
                new byte[]{1},
                deserializedValue,
                null,
                cacheOperation
        );
        return new CacheContext(input);
    }

    @Nested
    @DisplayName("shouldHandle")
    class ShouldHandleTests {

        @Test
        @DisplayName("returns true for PUT operation")
        void shouldHandle_putOperation_returnsTrue() {
            CacheContext context = createContext(CacheOperation.PUT, "value");

            boolean result = handler.shouldHandle(context);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns true for PUT_IF_ABSENT operation")
        void shouldHandle_putIfAbsentOperation_returnsTrue() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT, "value");

            boolean result = handler.shouldHandle(context);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false for GET operation")
        void shouldHandle_getOperation_returnsFalse() {
            CacheContext context = createContext(CacheOperation.GET, "value");

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false for REMOVE operation")
        void shouldHandle_removeOperation_returnsFalse() {
            CacheContext context = createContext(CacheOperation.REMOVE, "value");

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false for CLEAN operation")
        void shouldHandle_cleanOperation_returnsFalse() {
            CacheContext context = createContext(CacheOperation.CLEAN, "value");

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("doHandle with null value")
    class DoHandleWithNullValueTests {

        @Test
        @DisplayName("skips remaining handlers when cacheNullValues is false")
        void doHandle_nullValue_notCacheable_returnsSkipAll() {
            CacheContext context = createContext(CacheOperation.PUT, null);
            when(nullValuePolicy.shouldCacheNull(any())).thenReturn(false);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldSkipAll()).isTrue();
            assertThat(result.result()).isEqualTo(CacheResult.success());
            verify(nullValuePolicy, never()).toStoreValue(any(), any());
        }

        @Test
        @DisplayName("continues chain when cacheNullValues is true")
        void doHandle_nullValue_cacheable_returnsContinueChain() {
            CacheContext context = createContext(CacheOperation.PUT, null);
            when(nullValuePolicy.shouldCacheNull(any())).thenReturn(true);
            when(nullValuePolicy.toStoreValue(eq(null), any())).thenReturn("NULL_PLACEHOLDER");

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldSkipAll()).isFalse();
            assertThat(context.getOutput().getStoreValue()).isEqualTo("NULL_PLACEHOLDER");
        }

        @Test
        @DisplayName("sets storeValue on context output")
        void doHandle_nullValue_setsStoreValue() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT, null);
            when(nullValuePolicy.shouldCacheNull(any())).thenReturn(true);
            when(nullValuePolicy.toStoreValue(eq(null), any())).thenReturn("NULL_PLACEHOLDER");

            handler.doHandle(context);

            assertThat(context.getOutput().getStoreValue()).isEqualTo("NULL_PLACEHOLDER");
        }
    }

    @Nested
    @DisplayName("doHandle with non-null value")
    class DoHandleWithNonNullValueTests {

        @Test
        @DisplayName("converts value to store format and continues chain")
        void doHandle_nonNullValue_convertsAndContinues() {
            Object originalValue = "test-value";
            Object storeValue = "serialized-test-value";
            CacheContext context = createContext(CacheOperation.PUT, originalValue);
            when(nullValuePolicy.toStoreValue(eq(originalValue), any())).thenReturn(storeValue);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldSkipAll()).isFalse();
            assertThat(context.getOutput().getStoreValue()).isEqualTo(storeValue);
        }

        @Test
        @DisplayName("returns continueChain result for PUT operation")
        void doHandle_putOperation_returnsContinueChain() {
            CacheContext context = createContext(CacheOperation.PUT, "value");
            when(nullValuePolicy.toStoreValue(any(), any())).thenReturn("value");

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(ChainDecision.CONTINUE);
        }

        @Test
        @DisplayName("returns continueChain result for PUT_IF_ABSENT operation")
        void doHandle_putIfAbsentOperation_returnsContinueChain() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT, "value");
            when(nullValuePolicy.toStoreValue(any(), any())).thenReturn("value");

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(ChainDecision.CONTINUE);
        }
    }

    @Nested
    @DisplayName("handle method integration")
    class HandleMethodIntegrationTests {

        @Test
        @DisplayName("continues to next handler when operation is not handled")
        void handle_getOperation_delegatesToNextHandler() {
            CacheHandler nextHandler = mock(CacheHandler.class);
            handler.setNext(nextHandler);
            CacheContext context = createContext(CacheOperation.GET, "value");
            when(nextHandler.handle(context)).thenReturn(HandlerResult.continueWith(CacheResult.success()));

            HandlerResult result = handler.handle(context);

            verify(nextHandler).handle(context);
            assertThat(result).isEqualTo(HandlerResult.continueWith(CacheResult.success()));
        }

        @Test
        @DisplayName("skips remaining when context is marked skipRemaining")
        void handle_skipRemaining_returnsContinueWithSuccess() {
            CacheContext context = createContext(CacheOperation.PUT, null);
            context.markSkipRemaining();

            HandlerResult result = handler.handle(context);

            assertThat(result).isEqualTo(HandlerResult.continueWith(CacheResult.success()));
        }
    }
}
