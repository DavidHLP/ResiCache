package io.github.davidhlp.spring.cache.redis.core.writer.chain;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CacheHandlerChain 单元测试
 */
class CacheHandlerChainTest {

    private CacheHandlerChain chain;

    @BeforeEach
    void setUp() {
        chain = new CacheHandlerChain();
    }

    @Test
    void testExecuteCallsPostProcessor() {
        // Given: 添加一个实现 PostProcessHandler 的处理器
        TestPostProcessor postProcessor = new TestPostProcessor();
        chain.addHandler(postProcessor);

        // When: 执行责任链
        CacheContext context = createTestContext();
        CacheResult result = chain.execute(context);

        // Then: 后置处理应该被调用
        assertThat(postProcessor.afterChainExecutionCalled).isTrue();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void testExecuteOnlyCallsPostProcessorWhenRequired() {
        // Given: 设置后置处理器为不需要执行
        TestPostProcessor postProcessor = new TestPostProcessor();
        postProcessor.required = false;
        chain.addHandler(postProcessor);

        // When: 执行责任链
        CacheContext context = createTestContext();
        chain.execute(context);

        // Then: 后置处理不应该被调用
        assertThat(postProcessor.afterChainExecutionCalled).isFalse();
    }

    @Test
    void testPostProcessorExceptionDoesNotAffectMainResult() {
        // Given: 后置处理器会抛出异常
        TestPostProcessor exceptionProcessor = new TestPostProcessor();
        exceptionProcessor.shouldThrowException = true;
        chain.addHandler(exceptionProcessor);

        // When: 执行责任链
        CacheContext context = createTestContext();
        CacheResult result = chain.execute(context);

        // Then: 主链结果应该正常返回
        assertThat(result.isSuccess()).isTrue();
        assertThat(exceptionProcessor.afterChainExecutionCalled).isTrue();
    }

    @Test
    void testMultiplePostProcessorsAllExecuted() {
        // Given: 添加多个后置处理器
        TestPostProcessor processor1 = new TestPostProcessor();
        TestPostProcessor processor2 = new TestPostProcessor();

        chain.addHandler(processor1);
        chain.addHandler(processor2);

        // When: 执行责任链
        CacheContext context = createTestContext();
        chain.execute(context);

        // Then: 所有后置处理器都应该被调用
        assertThat(processor1.afterChainExecutionCalled).isTrue();
        assertThat(processor2.afterChainExecutionCalled).isTrue();
    }

    private CacheContext createTestContext() {
        return CacheContext.builder()
                .operation(CacheOperation.PUT)
                .cacheName("test-cache")
                .redisKey("test:key")
                .actualKey("test:key")
                .build();
    }

    /**
     * 测试用后置处理器
     * <p>实现 CacheHandler 和 PostProcessHandler 接口
     */
    static class TestPostProcessor implements CacheHandler, PostProcessHandler {
        private CacheHandler next;
        boolean afterChainExecutionCalled = false;
        boolean required = true;
        boolean shouldThrowException = false;

        @Override
        public CacheHandler getNext() {
            return next;
        }

        @Override
        public void setNext(CacheHandler next) {
            this.next = next;
        }

        @Override
        public HandlerResult handle(CacheContext context) {
            // 继续执行链（到达末尾时返回成功）
            if (getNext() != null) {
                return getNext().handle(context);
            }
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
            return required;
        }
    }
}
