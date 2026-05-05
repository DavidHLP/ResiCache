package io.github.davidhlp.spring.cache.redis.core.writer.chain;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.CacheContext;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.CacheHandler;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.HandlerResult;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.PostProcessHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheHandlerChain 单元测试
 */
@DisplayName("CacheHandlerChain Tests")
class CacheHandlerChainTest {

    private CacheHandlerChain chain;

    @BeforeEach
    void setUp() {
        chain = new CacheHandlerChain();
    }

    private CacheContext createTestContext() {
        return CacheContext.builder()
                .operation(CacheOperation.GET)
                .cacheName("test-cache")
                .redisKey("test:key")
                .actualKey("test:key")
                .build();
    }

    @Nested
    @DisplayName("addHandler")
    class AddHandlerTests {

        @Test
        @DisplayName("添加单个处理器")
        void addHandler_singleHandler_chainSizeIsOne() {
            CacheHandler handler = new TestCacheHandler();
            chain.addHandler(handler);
            assertThat(chain.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("添加多个处理器形成链")
        void addHandler_multipleHandlers_chainSizeCorrect() {
            chain.addHandler(new TestCacheHandler());
            chain.addHandler(new TestCacheHandler());
            chain.addHandler(new TestCacheHandler());
            assertThat(chain.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("返回 this 支持链式调用")
        void addHandler_returnsChainForChaining() {
            CacheHandler handler = new TestCacheHandler();
            CacheHandlerChain returned = chain.addHandler(handler);
            assertThat(returned).isSameAs(chain);
        }
    }

    @Nested
    @DisplayName("execute")
    class ExecuteTests {

        @Test
        @DisplayName("空链返回成功结果")
        void execute_emptyChain_returnsSuccess() {
            CacheContext context = createTestContext();
            CacheResult result = chain.execute(context);
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("单处理器执行成功")
        void execute_singleHandler_executesSuccessfully() {
            AtomicBoolean handlerCalled = new AtomicBoolean(false);
            CacheHandler handler = new TestCacheHandler() {
                @Override
                public HandlerResult handle(CacheContext context) {
                    handlerCalled.set(true);
                    return HandlerResult.continueWith(CacheResult.success());
                }
            };
            chain.addHandler(handler);

            CacheContext context = createTestContext();
            CacheResult result = chain.execute(context);

            assertThat(handlerCalled.get()).isTrue();
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("多处理器按顺序执行")
        void execute_multipleHandlers_executesInOrder() {
            AtomicBoolean firstCalled = new AtomicBoolean(false);
            AtomicBoolean secondCalled = new AtomicBoolean(false);

            CacheHandler first = new TestCacheHandler() {
                @Override
                public HandlerResult handle(CacheContext context) {
                    firstCalled.set(true);
                    return getNext() != null ? getNext().handle(context) : HandlerResult.continueWith(CacheResult.success());
                }
            };

            CacheHandler second = new TestCacheHandler() {
                @Override
                public HandlerResult handle(CacheContext context) {
                    secondCalled.set(true);
                    return HandlerResult.continueWith(CacheResult.success());
                }
            };

            chain.addHandler(first);
            chain.addHandler(second);

            CacheContext context = createTestContext();
            chain.execute(context);

            assertThat(firstCalled.get()).isTrue();
            assertThat(secondCalled.get()).isTrue();
        }

        @Test
        @DisplayName("返回 null 的结果被替换为成功结果")
        void execute_nullResult_replacedWithSuccess() {
            CacheHandler handler = new TestCacheHandler() {
                @Override
                public HandlerResult handle(CacheContext context) {
                    return HandlerResult.continueWith(null);
                }
            };
            chain.addHandler(handler);

            CacheContext context = createTestContext();
            CacheResult result = chain.execute(context);

            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("PostProcessHandler")
    class PostProcessHandlerTests {

        @Test
        @DisplayName("后置处理器在链执行后被调用")
        void execute_withPostProcessor_calledAfterChain() {
            AtomicBoolean postProcessorCalled = new AtomicBoolean(false);
            TestPostProcessor postProcessor = new TestPostProcessor(postProcessorCalled);

            chain.addHandler(new TestCacheHandler());
            chain.addHandler(postProcessor);

            CacheContext context = createTestContext();
            chain.execute(context);

            assertThat(postProcessorCalled.get()).isTrue();
        }

        @Test
        @DisplayName("requiresPostProcess 返回 false 时不调用后置处理")
        void execute_postProcessorNotRequired_notCalled() {
            AtomicBoolean postProcessorCalled = new AtomicBoolean(false);
            TestPostProcessor postProcessor = new TestPostProcessor(postProcessorCalled, false);

            chain.addHandler(new TestCacheHandler());
            chain.addHandler(postProcessor);

            CacheContext context = createTestContext();
            chain.execute(context);

            assertThat(postProcessorCalled.get()).isFalse();
        }
    }

    @Nested
    @DisplayName("clear")
    class ClearTests {

        @Test
        @DisplayName("清空后链大小为 0")
        void clear_afterAddingHandlers_sizeIsZero() {
            chain.addHandler(new TestCacheHandler());
            chain.addHandler(new TestCacheHandler());
            chain.clear();
            assertThat(chain.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("清空后执行返回成功")
        void clear_emptyChain_executesSuccessfully() {
            chain.addHandler(new TestCacheHandler());
            chain.clear();

            CacheContext context = createTestContext();
            CacheResult result = chain.execute(context);
            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("getHandlerNames")
    class GetHandlerNamesTests {

        @Test
        @DisplayName("返回所有处理器名称")
        void getHandlerNames_returnsAllNames() {
            chain.addHandler(new TestCacheHandler());
            chain.addHandler(new AnotherTestHandler());

            var names = chain.getHandlerNames();
            assertThat(names).containsExactly("TestCacheHandler", "AnotherTestHandler");
        }

        @Test
        @DisplayName("空链返回空列表")
        void getHandlerNames_emptyChain_returnsEmptyList() {
            assertThat(chain.getHandlerNames()).isEmpty();
        }
    }

    @Nested
    @DisplayName("size")
    class SizeTests {

        @Test
        @DisplayName("空链大小为 0")
        void size_emptyChain_returnsZero() {
            assertThat(chain.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("添加处理器后大小正确")
        void size_withHandlers_returnsCorrectSize() {
            chain.addHandler(new TestCacheHandler());
            chain.addHandler(new TestCacheHandler());
            assertThat(chain.size()).isEqualTo(2);
        }
    }

    // Test handler implementations
    static class TestCacheHandler implements CacheHandler {
        private CacheHandler next;

        @Override
        public HandlerResult handle(CacheContext context) {
            if (next != null) {
                return next.handle(context);
            }
            return HandlerResult.continueWith(CacheResult.success());
        }

        @Override
        public CacheHandler getNext() {
            return next;
        }

        @Override
        public void setNext(CacheHandler next) {
            this.next = next;
        }
    }

    static class AnotherTestHandler implements CacheHandler {
        private CacheHandler next;

        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

        @Override
        public CacheHandler getNext() {
            return next;
        }

        @Override
        public void setNext(CacheHandler next) {
            this.next = next;
        }
    }

    static class TestPostProcessor implements CacheHandler, PostProcessHandler {
        private CacheHandler next;
        private final AtomicBoolean called;
        private final boolean requiresPostProcess;

        TestPostProcessor(AtomicBoolean called) {
            this(called, true);
        }

        TestPostProcessor(AtomicBoolean called, boolean requiresPostProcess) {
            this.called = called;
            this.requiresPostProcess = requiresPostProcess;
        }

        @Override
        public HandlerResult handle(CacheContext context) {
            return getNext() != null ? getNext().handle(context) : HandlerResult.continueWith(CacheResult.success());
        }

        @Override
        public CacheHandler getNext() {
            return next;
        }

        @Override
        public void setNext(CacheHandler next) {
            this.next = next;
        }

        @Override
        public void afterChainExecution(CacheContext context, CacheResult result) {
            called.set(true);
        }

        @Override
        public boolean requiresPostProcess(CacheContext context) {
            return requiresPostProcess;
        }
    }
}
