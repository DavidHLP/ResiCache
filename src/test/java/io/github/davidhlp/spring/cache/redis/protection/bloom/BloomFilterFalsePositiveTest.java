package io.github.davidhlp.spring.cache.redis.protection.bloom;

import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.model.*;


import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bloom Filter False Positive Behavior Tests
 *
 * <p>Tests writer-layer Bloom filter short-circuit behavior on GET operations.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Bloom Filter False Positive Tests")
class BloomFilterFalsePositiveTest {

    @Mock
    private BloomSupport bloomSupport;

    @Mock
    private CacheStatisticsCollector statistics;

    private BloomFilterHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BloomFilterHandler(bloomSupport, statistics, null);
    }

    private CacheContext createContext(CacheOperation operation) {
        RedisCacheableOperation cacheOp = RedisCacheableOperation.builder()
                .name("test-cache")
                .cacheNames("test-cache")
                .useBloomFilter(true)
                .build();
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
    @DisplayName("Writer-layer GET bloom filter short-circuit")
    class WriterLayerGetBloomTests {

        @Test
        @DisplayName("GET terminates with miss when bloom filter rejects key")
        void get_bloomRejects_terminatesWithMiss() {
            CacheContext context = createContext(CacheOperation.GET);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            verify(bloomSupport).mightContain(anyString(), anyString());
            verify(statistics).incMisses(anyString());
        }

        @Test
        @DisplayName("GET continues chain when bloom filter allows key")
        void get_bloomAllows_continuesChain() {
            when(bloomSupport.mightContain(anyString(), anyString())).thenReturn(true);
            CacheContext context = createContext(CacheOperation.GET);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isFalse();
            verify(bloomSupport).mightContain(anyString(), anyString());
            verify(statistics, never()).incMisses(anyString());
        }
    }

    @Nested
    @DisplayName("Post-processing behavior")
    class PostProcessingScenarios {

        @Test
        @DisplayName("PUT success adds key to bloom filter")
        void putSuccess_addsToBloomFilter() {
            CacheContext context = createContext(CacheOperation.PUT);
            context.setAttribute("bloom.postProcess", true);
            CacheResult chainResult = CacheResult.success();

            handler.afterChainExecution(context, chainResult);

            verify(bloomSupport).add("test-cache", "key");
        }

        @Test
        @DisplayName("GET success does not trigger bloom add (no post-process for GET)")
        void getSuccess_noBloomAdd() {
            CacheContext context = createContext(CacheOperation.GET);
            CacheResult chainResult = CacheResult.miss();

            handler.afterChainExecution(context, chainResult);

            verify(bloomSupport, never()).add(anyString(), anyString());
        }
    }
}
