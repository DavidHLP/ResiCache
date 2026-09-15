package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.FlowControl;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NullValueHandler 单元测试。
 *
 * <p>null 的缓存判定和写入值决议均由处理器直接拥有。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NullValueHandler Tests")
class NullValueHandlerTest {

    @Mock
    private RedisCacheableOperation cacheOperation;

    private NullValueHandler handler;

    @BeforeEach
    void setUp() {
        handler = new NullValueHandler();
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
        void shouldHandle_putOperation_returnsTrue() {
            assertThat(handler.shouldHandle(createContext(CacheOperation.PUT, "value"))).isTrue();
        }

        @Test
        void shouldHandle_putIfAbsentOperation_returnsTrue() {
            assertThat(handler.shouldHandle(
                    createContext(CacheOperation.PUT_IF_ABSENT, "value"))).isTrue();
        }

        @Test
        void shouldHandle_getOperation_returnsFalse() {
            assertThat(handler.shouldHandle(createContext(CacheOperation.GET, "value"))).isFalse();
        }

        @Test
        void shouldHandle_removeOperation_returnsFalse() {
            assertThat(handler.shouldHandle(createContext(CacheOperation.REMOVE, "value"))).isFalse();
        }

        @Test
        void shouldHandle_cleanOperation_returnsFalse() {
            assertThat(handler.shouldHandle(createContext(CacheOperation.CLEAN, "value"))).isFalse();
        }
    }

    @Nested
    @DisplayName("null value decisions")
    class NullValueDecisionTests {

        @Test
        void doHandle_nullValue_notCacheable_returnsSkipAll() {
            CacheContext context = createContext(CacheOperation.PUT, null);
            when(cacheOperation.isCacheNullValues()).thenReturn(false);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(FlowControl.SKIP_ALL);
            assertThat(result.result()).isEqualTo(CacheResult.success());
        }

        @Test
        void doHandle_nullValue_cacheable_continuesWithNullStoreValue() {
            CacheContext context = createContext(CacheOperation.PUT, null);
            when(cacheOperation.isCacheNullValues()).thenReturn(true);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(FlowControl.CONTINUE);
            assertThat(context.getNullDecision().storeValue()).isNull();
        }

        @Test
        void doHandle_nonNullValue_keepsOriginalStoreValue() {
            Object value = "test-value";
            CacheContext context = createContext(CacheOperation.PUT, value);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(FlowControl.CONTINUE);
            assertThat(context.getNullDecision().storeValue()).isSameAs(value);
        }
    }

    @Nested
    @DisplayName("handle method integration")
    class HandleMethodIntegrationTests {

        @Test
        void handle_getOperation_returnsContinueChainWithoutAdvancing() {
            CacheHandler nextHandler = mock(CacheHandler.class);
            CacheContext context = createContext(CacheOperation.GET, "value");

            HandlerResult result = handler.handle(context);

            verify(nextHandler, never()).handle(context);
            assertThat(result.decision()).isEqualTo(FlowControl.CONTINUE);
        }

        @Test
        void handle_skipRemaining_isNoLongerHandledByHandler() {
            CacheContext context = createContext(CacheOperation.PUT, null);
            context.markSkipRemaining();
            when(cacheOperation.isCacheNullValues()).thenReturn(false);

            HandlerResult result = handler.handle(context);

            assertThat(result.decision()).isEqualTo(FlowControl.SKIP_ALL);
        }
    }
}
