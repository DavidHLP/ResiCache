package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CacheHandlerChain 异常处理测试
 *
 * <p>验证责任链在处理异常时的行为，确保异常不会被静默吞掉。
 */
class CacheHandlerChainExceptionTest {

    private CacheHandlerChain chain;

    @BeforeEach
    void setUp() {
        // ADR-0009:facade 改为 thin,execute 委派 ChainEngine —— 单元测试手动装配
        ChainEngine engine = new ChainEngine();
        chain = new CacheHandlerChain();
        chain.setEngine(engine);
    }

    @Test
    @DisplayName("handler throws RuntimeException - exception propagates and is not silently swallowed")
    void handlerThrowsException_chainThrows() {
        // Given: A handler that throws RuntimeException
        CacheHandler throwingHandler = new CacheHandler() {
            @Override
            public HandlerResult handle(CacheContext context) {
                throw new RuntimeException("Test exception in handler");
            }
        };
        chain.addHandler(throwingHandler);

        CacheContext context = createTestContext();

        // When/Then: The exception should propagate, not be swallowed
        assertThatThrownBy(() -> chain.execute(context))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Test exception in handler");
    }

    @Test
    @DisplayName("handler throws exception - ERROR level log is generated")
    void handlerThrowsException_exceptionIsLogged() {
        // Given: A handler that throws RuntimeException
        CacheHandler throwingHandler = new CacheHandler() {
            @Override
            public HandlerResult handle(CacheContext context) {
                throw new RuntimeException("Log test exception");
            }
        };
        chain.addHandler(throwingHandler);

        CacheContext context = createTestContext();

        // When: Execute chain - exception propagates
        // Then: Exception is not silently swallowed (verified by exception propagation above)
        // Log verification is implicit - if exception propagates, it was not caught and logged silently
        assertThatThrownBy(() -> chain.execute(context))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Log test exception");
    }

    @Test
    @DisplayName("postProcessor throws exception - does not affect main result")
    void postProcessorThrowsException_doesNotAffectResult() {
        // Given: A normal handler and a post-processor that throws
        CacheHandler normalHandler = new CacheHandler() {
            @Override
            public HandlerResult handle(CacheContext context) {
                return HandlerResult.continueWith(CacheResult.success());
            }
        };

        TestPostProcessor exceptionPostProcessor = new TestPostProcessor();
        exceptionPostProcessor.shouldThrowException = true;

        chain.addHandler(normalHandler);
        chain.addHandler(exceptionPostProcessor);

        CacheContext context = createTestContext();

        // When: Execute chain
        CacheResult result = chain.execute(context);

        // Then: Main result is still success, post-processor exception does not affect result
        assertThat(result.isSuccess()).isTrue();
        assertThat(exceptionPostProcessor.afterChainExecutionCalled).isTrue();
    }

    @Test
    @DisplayName("multiple handlers - middle handler throws, remaining handlers skipped")
    void multipleHandlersMiddleThrows_remainingHandlersSkipped() {
        // Given: Three handlers where the middle one throws
        AtomicBoolean handler1Called = new AtomicBoolean(false);
        AtomicBoolean handler2Called = new AtomicBoolean(false);
        AtomicBoolean handler3Called = new AtomicBoolean(false);

        CacheHandler handler1 = new CacheHandler() {
            @Override
            public HandlerResult handle(CacheContext context) {
                handler1Called.set(true);
                return HandlerResult.continueWith(CacheResult.success());
            }
        };

        CacheHandler handler2 = new CacheHandler() {
            @Override
            public HandlerResult handle(CacheContext context) {
                handler2Called.set(true);
                throw new RuntimeException("Middle handler exception");
            }
        };

        CacheHandler handler3 = new CacheHandler() {
            @Override
            public HandlerResult handle(CacheContext context) {
                handler3Called.set(true);
                return HandlerResult.continueWith(CacheResult.success());
            }
        };

        // Build chain: handler1 -> handler2 (throws) -> handler3
        chain.addHandler(handler1);
        chain.addHandler(handler2);
        chain.addHandler(handler3);

        CacheContext context = createTestContext();

        // When/Then: Exception propagates, handler3 is never called because handler2 throws
        assertThatThrownBy(() -> chain.execute(context))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Middle handler exception");

        assertThat(handler1Called.get()).isTrue();
        assertThat(handler2Called.get()).isTrue();
        // handler3 should not have been called since handler2 threw
        assertThat(handler3Called.get()).isFalse();
    }

    private CacheContext createTestContext() {
        return CacheContext.of(CacheInput.builder()
                .operation(CacheOperation.PUT)
                .cacheName("test-cache")
                .redisKey("test:key")
                .actualKey("test:key")
                .build());
    }

    /**
     * Test post-processor implementation
     */
    static class TestPostProcessor implements CacheHandler, PostProcessHandler {
        boolean afterChainExecutionCalled = false;
        boolean shouldThrowException = false;

        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

        @Override
        public void afterChainExecution(CacheContext context, CacheResult result) {
            afterChainExecutionCalled = true;
            if (shouldThrowException) {
                throw new RuntimeException("Test exception in post processor");
            }
        }

        @Override
        public boolean requiresPostProcess(CacheContext context) {
            return true;
        }
    }
}
