package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.BloomSupport;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.cache.CacheStatisticsCollector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BloomFilterHandler 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BloomFilterHandler Tests")
class BloomFilterHandlerTest {

    @Mock
    private BloomSupport bloomSupport;

    @Mock
    private CacheStatisticsCollector statistics;

    @Mock
    private RedisCacheableOperation cacheOperation;

    private BloomFilterHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BloomFilterHandler(bloomSupport, statistics);
    }

    private CacheContext createContext(CacheOperation operation, RedisCacheableOperation cacheOp) {
        CacheInput input = new CacheInput(
                operation,
                "test-cache",
                "test:key",
                "key",
                new byte[]{1},
                "value",
                null,
                cacheOp
        );
        return new CacheContext(input);
    }

    @Nested
    @DisplayName("shouldHandle")
    class ShouldHandleTests {

        @Test
        @DisplayName("returns true when cacheOperation.useBloomFilter is true")
        void shouldHandle_bloomFilterEnabled_returnsTrue() {
            when(cacheOperation.isUseBloomFilter()).thenReturn(true);
            CacheContext context = createContext(CacheOperation.GET, cacheOperation);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false when cacheOperation.useBloomFilter is false")
        void shouldHandle_bloomFilterDisabled_returnsFalse() {
            when(cacheOperation.isUseBloomFilter()).thenReturn(false);
            CacheContext context = createContext(CacheOperation.GET, cacheOperation);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when cacheOperation is null")
        void shouldHandle_nullCacheOperation_returnsFalse() {
            CacheContext context = createContext(CacheOperation.GET, null);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("doHandle - GET operation")
    class GetOperationTests {

        @Test
        @DisplayName("terminates chain when bloom filter rejects key")
        void handleGet_bloomFilterRejects_terminatesChain() {
            when(bloomSupport.mightContain(anyString(), anyString())).thenReturn(false);
            CacheContext context = createContext(CacheOperation.GET, cacheOperation);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result()).isNotNull();
            assertThat(result.result().isRejectedByBloomFilter()).isTrue();
            verify(statistics).incMisses("test-cache");
        }

        @Test
        @DisplayName("continues chain when bloom filter might contain key")
        void handleGet_bloomFilterMightContain_continuesChain() {
            when(bloomSupport.mightContain(anyString(), anyString())).thenReturn(true);
            CacheContext context = createContext(CacheOperation.GET, cacheOperation);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isFalse();
        }

        @Test
        @DisplayName("increments miss counter when bloom filter rejects")
        void handleGet_bloomFilterRejects_incrementsMissCounter() {
            when(bloomSupport.mightContain(anyString(), anyString())).thenReturn(false);
            CacheContext context = createContext(CacheOperation.GET, cacheOperation);

            handler.doHandle(context);

            verify(statistics).incMisses("test-cache");
        }
    }

    @Nested
    @DisplayName("doHandle - PUT operation")
    class PutOperationTests {

        @Test
        @DisplayName("marks post process and continues chain")
        void handlePut_marksPostProcessAndContinues() {
            CacheContext context = createContext(CacheOperation.PUT, cacheOperation);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isFalse();
            assertThat(Boolean.class.cast(context.getAttribute("bloom.postProcess"))).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("doHandle - PUT_IF_ABSENT operation")
    class PutIfAbsentOperationTests {

        @Test
        @DisplayName("marks post process and continues chain")
        void handlePutIfAbsent_marksPostProcessAndContinues() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT, cacheOperation);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isFalse();
            assertThat(Boolean.class.cast(context.getAttribute("bloom.postProcess"))).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("doHandle - CLEAN operation")
    class CleanOperationTests {

        @Test
        @DisplayName("marks post process and continues chain")
        void handleClean_marksPostProcessAndContinues() {
            CacheContext context = createContext(CacheOperation.CLEAN, cacheOperation);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isFalse();
            assertThat(Boolean.class.cast(context.getAttribute("bloom.postProcess"))).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("requiresPostProcess")
    class RequiresPostProcessTests {

        @Test
        @DisplayName("returns true when post process is marked")
        void requiresPostProcess_marked_returnsTrue() {
            CacheContext context = createContext(CacheOperation.PUT, cacheOperation);
            context.setAttribute("bloom.postProcess", true);

            boolean result = handler.requiresPostProcess(context);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false when post process is not marked")
        void requiresPostProcess_notMarked_returnsFalse() {
            CacheContext context = createContext(CacheOperation.PUT, cacheOperation);

            boolean result = handler.requiresPostProcess(context);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("afterChainExecution")
    class AfterChainExecutionTests {

        @Test
        @DisplayName("adds key to bloom filter on PUT success")
        void afterChainExecution_putSuccess_addsToBloomFilter() {
            CacheContext context = createContext(CacheOperation.PUT, cacheOperation);
            context.setAttribute("bloom.postProcess", true);
            CacheResult chainResult = CacheResult.success();

            handler.afterChainExecution(context, chainResult);

            verify(bloomSupport).add(eq("test-cache"), eq("key"));
        }

        @Test
        @DisplayName("adds key to bloom filter on PUT_IF_ABSENT success")
        void afterChainExecution_putIfAbsentSuccess_addsToBloomFilter() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT, cacheOperation);
            context.setAttribute("bloom.postProcess", true);
            CacheResult chainResult = CacheResult.success();

            handler.afterChainExecution(context, chainResult);

            verify(bloomSupport).add(eq("test-cache"), eq("key"));
        }

        @Test
        @DisplayName("clears bloom filter on CLEAN success with pattern ending wildcard")
        void afterChainExecution_cleanWithPattern_clearsBloomFilter() {
            CacheContext context = createContext(CacheOperation.CLEAN, cacheOperation);
            context.setAttribute("bloom.postProcess", true);
            context.setKeyPattern("test:*");
            CacheResult chainResult = CacheResult.success();

            handler.afterChainExecution(context, chainResult);

            verify(bloomSupport).clear("test-cache");
        }

        @Test
        @DisplayName("does not clear bloom filter on CLEAN without wildcard pattern")
        void afterChainExecution_cleanWithoutWildcard_doesNotClear() {
            CacheContext context = createContext(CacheOperation.CLEAN, cacheOperation);
            context.setAttribute("bloom.postProcess", true);
            context.setKeyPattern("test:single");
            CacheResult chainResult = CacheResult.success();

            handler.afterChainExecution(context, chainResult);

            verify(bloomSupport, never()).clear(anyString());
        }

        @Test
        @DisplayName("does nothing when result is not success")
        void afterChainExecution_notSuccess_doesNothing() {
            CacheContext context = createContext(CacheOperation.PUT, cacheOperation);
            context.setAttribute("bloom.postProcess", true);
            CacheResult chainResult = CacheResult.failure(new RuntimeException("test"));

            handler.afterChainExecution(context, chainResult);

            verify(bloomSupport, never()).add(anyString(), anyString());
        }

        @Test
        @DisplayName("does nothing when context is skip remaining")
        void afterChainExecution_skipRemaining_doesNothing() {
            CacheContext context = createContext(CacheOperation.PUT, cacheOperation);
            context.setAttribute("bloom.postProcess", true);
            context.markSkipRemaining();
            CacheResult chainResult = CacheResult.success();

            handler.afterChainExecution(context, chainResult);

            verify(bloomSupport, never()).add(anyString(), anyString());
        }

        @Test
        @DisplayName("skips processing when context is null")
        void afterChainExecution_nullContext_doesNothing() {
            handler.afterChainExecution(null, CacheResult.success());
            // Should not throw
        }

        @Test
        @DisplayName("skips processing when result is null")
        void afterChainExecution_nullResult_doesNothing() {
            handler.afterChainExecution(createContext(CacheOperation.PUT, cacheOperation), null);
            // Should not throw
        }
    }
}
