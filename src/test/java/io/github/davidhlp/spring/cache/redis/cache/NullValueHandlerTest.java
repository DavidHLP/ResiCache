package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.FlowControl;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.NullDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    private DefaultNullValuePolicy nullValuePolicy;

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
            when(cacheOperation.isCacheNullValues()).thenReturn(false);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(FlowControl.SKIP_ALL);
            assertThat(result.result()).isEqualTo(CacheResult.success());
            verify(nullValuePolicy, never()).toStoreValue(any(), anyBoolean());
        }

        @Test
        @DisplayName("continues chain when cacheNullValues is true")
        void doHandle_nullValue_cacheable_returnsContinueChain() {
            CacheContext context = createContext(CacheOperation.PUT, null);
            when(cacheOperation.isCacheNullValues()).thenReturn(true);
            when(nullValuePolicy.toStoreValue(eq(null), eq(true))).thenReturn("NULL_PLACEHOLDER");

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isNotEqualTo(FlowControl.SKIP_ALL);
            assertThat(context.getNullDecision().storeValue()).isEqualTo("NULL_PLACEHOLDER");
        }

        @Test
        @DisplayName("sets storeValue on context output")
        void doHandle_nullValue_setsStoreValue() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT, null);
            when(cacheOperation.isCacheNullValues()).thenReturn(true);
            when(nullValuePolicy.toStoreValue(eq(null), eq(true))).thenReturn("NULL_PLACEHOLDER");

            handler.doHandle(context);

            assertThat(context.getNullDecision().storeValue()).isEqualTo("NULL_PLACEHOLDER");
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
            when(nullValuePolicy.toStoreValue(eq(originalValue), anyBoolean())).thenReturn(storeValue);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isNotEqualTo(FlowControl.SKIP_ALL);
            assertThat(context.getNullDecision().storeValue()).isEqualTo(storeValue);
        }

        @Test
        @DisplayName("returns continueChain result for PUT operation")
        void doHandle_putOperation_returnsContinueChain() {
            CacheContext context = createContext(CacheOperation.PUT, "value");
            when(nullValuePolicy.toStoreValue(any(), anyBoolean())).thenReturn("value");

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(FlowControl.CONTINUE);
        }

        @Test
        @DisplayName("returns continueChain result for PUT_IF_ABSENT operation")
        void doHandle_putIfAbsentOperation_returnsContinueChain() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT, "value");
            when(nullValuePolicy.toStoreValue(any(), anyBoolean())).thenReturn("value");

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(FlowControl.CONTINUE);
        }
    }

    @Nested
    @DisplayName("handle method integration (handler 不再自行推进/短路,推进与短路由 ChainEngine 负责)")
    class HandleMethodIntegrationTests {

        @Test
        @DisplayName("GET operation 不在 shouldHandle 范围,handler.handle 返回 continueChain,不调 next")
        void handle_getOperation_returnsContinueChainWithoutAdvancing() {
            // AbstractCacheHandler.handle 只做"shouldHandle ? doHandle : continueChain";
            // 链推进由 ChainEngine 负责 —— handler 自身不持有也不调 next。
            CacheHandler nextHandler = mock(CacheHandler.class);
            CacheContext context = createContext(CacheOperation.GET, "value");

            HandlerResult result = handler.handle(context);

            // handler 不主动推进 —— 推进是 engine 的职责
            verify(nextHandler, never()).handle(context);
            // GET 不在 shouldHandle 范围,直接返回 continueChain
            assertThat(result.decision()).isEqualTo(FlowControl.CONTINUE);
        }

        @Test
        @DisplayName("skipRemaining 短路改由 ChainEngine 处理,handler 自身不感知")
        void handle_skipRemaining_isNoLongerHandledByHandler() {
            // AbstractCacheHandler.handle 顶部无 isSkipRemaining() 短路;
            // 该短路由 ChainEngine.driveChain 在节点循环开头检测。
            // 断言:即使 context 已 markSkipRemaining,handler 按 shouldHandle/doHandle 正常求值
            // (PUT + null 走到 NullValueHandler.doHandle → skipAll 分支)。
            CacheContext context = createContext(CacheOperation.PUT, null);
            context.markSkipRemaining();
            // 即使 skipRemaining 已置位,NullValueHandler.doHandle 照常执行(短路是 engine 责任)
            when(cacheOperation.isCacheNullValues()).thenReturn(false);

            HandlerResult result = handler.handle(context);

            // doHandle 内 null 路径 → cacheNullValues=false → skipAll
            assertThat(result.decision()).isEqualTo(FlowControl.SKIP_ALL);
        }
    }
}
