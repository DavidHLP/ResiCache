package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.BloomFilterHandler;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.CacheContext;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.CacheInput;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.HandlerResult;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bloom Filter False Positive Behavior Tests
 *
 * <p>Tests that the chain continues correctly when Bloom filter returns true (might contain)
 * but Redis actually has no value. This is the key false positive scenario.
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
        handler = new BloomFilterHandler(bloomSupport, statistics);
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

    private CacheContext createContextWithOp(CacheOperation operation, RedisCacheableOperation cacheOp) {
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
    @DisplayName("False Positive Scenario: Bloom says might contain but Redis has no value")
    class FalsePositiveScenarios {

        @Test
        @DisplayName("chain continues when bloom says might contain (false positive)")
        void bloomSaysMightContainButRedisMiss_continuesToRedis() {
            // Given: Bloom filter returns true (might contain - false positive case)
            when(bloomSupport.mightContain(anyString(), anyString())).thenReturn(true);
            RedisCacheableOperation cacheOp = RedisCacheableOperation.builder()
                    .name("test-cache")
                    .cacheNames("test-cache")
                    .useBloomFilter(true)
                    .build();
            CacheContext context = createContextWithOp(CacheOperation.GET, cacheOp);

            // When: Handler processes the GET
            HandlerResult result = handler.doHandle(context);

            // Then: Chain continues (does NOT terminate)
            assertThat(result.shouldTerminate()).isFalse();
            assertThat(result.decision().name()).isEqualTo("CONTINUE");

            // And: Post-processing is marked for later
            assertThat((Boolean) context.getAttribute("bloom.get.positive")).isTrue();

            // And: Bloom filter add is NOT called yet (deferred to post-processing)
            verify(bloomSupport, never()).add(anyString(), anyString());
        }

        @Test
        @DisplayName("bloom positive does not block chain prematurely on PUT")
        void bloomSaysMightContain_chainDoesNotBlockPrematurely() {
            // Given: Bloom filter returns true for a PUT operation
            RedisCacheableOperation cacheOp = RedisCacheableOperation.builder()
                    .name("test-cache")
                    .cacheNames("test-cache")
                    .useBloomFilter(true)
                    .build();
            CacheContext context = createContextWithOp(CacheOperation.PUT, cacheOp);

            // When: Handler processes the PUT
            HandlerResult result = handler.doHandle(context);

            // Then: Chain continues (does not block)
            assertThat(result.shouldTerminate()).isFalse();
            assertThat(result.decision().name()).isEqualTo("CONTINUE");

            // And: Post-process is marked for bloom filter update
            assertThat((Boolean) context.getAttribute("bloom.postProcess")).isTrue();
        }

        @Test
        @DisplayName("chain terminates immediately when bloom says definitely not contain")
        void bloomSaysDefinitelyNotContain_terminatesChain() {
            // Given: Bloom filter returns false (definitely does not exist)
            when(bloomSupport.mightContain(anyString(), anyString())).thenReturn(false);
            RedisCacheableOperation cacheOp = RedisCacheableOperation.builder()
                    .name("test-cache")
                    .cacheNames("test-cache")
                    .useBloomFilter(true)
                    .build();
            CacheContext context = createContextWithOp(CacheOperation.GET, cacheOp);

            // When: Handler processes the GET
            HandlerResult result = handler.doHandle(context);

            // Then: Chain terminates immediately
            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result()).isNotNull();
            assertThat(result.result().isRejectedByBloomFilter()).isTrue();

            // And: Statistics are updated
            verify(statistics).incMisses("test-cache");
        }
    }

    @Nested
    @DisplayName("Post-processing behavior on false positive")
    class PostProcessingScenarios {

        @Test
        @DisplayName("adds key to bloom filter when GET returns miss after false positive")
        void addsKeyToBloomFilterWhenCacheMissAfterFalsePositive() {
            // Given: GET with false positive was handled
            when(bloomSupport.mightContain(anyString(), anyString())).thenReturn(true);
            RedisCacheableOperation cacheOp = RedisCacheableOperation.builder()
                    .name("test-cache")
                    .cacheNames("test-cache")
                    .useBloomFilter(true)
                    .build();
            CacheContext context = createContextWithOp(CacheOperation.GET, cacheOp);
            handler.doHandle(context);

            // And: bloom.get.positive attribute is set for post-processing
            assertThat((Boolean) context.getAttribute("bloom.get.positive")).isTrue();

            // And: requiresPostProcess returns false for GET (uses different post-process path)
            assertThat(handler.requiresPostProcess(context)).isFalse();

            // When: Chain completes with cache miss (result.isHit() = false)
            CacheResult chainResult = CacheResult.miss();
            handler.afterChainExecution(context, chainResult);

            // Then: Key is added to bloom filter to prevent future false positive queries
            verify(bloomSupport).add("test-cache", "key");
        }

        @Test
        @DisplayName("does not add key when GET returns hit after false positive")
        void doesNotAddKeyWhenCacheHitAfterFalsePositive() {
            // Given: GET with false positive was handled
            when(bloomSupport.mightContain(anyString(), anyString())).thenReturn(true);
            RedisCacheableOperation cacheOp = RedisCacheableOperation.builder()
                    .name("test-cache")
                    .cacheNames("test-cache")
                    .useBloomFilter(true)
                    .build();
            CacheContext context = createContextWithOp(CacheOperation.GET, cacheOp);
            handler.doHandle(context);

            // When: Chain completes with cache hit (key actually exists)
            CacheResult chainResult = CacheResult.success(new byte[]{1});
            handler.afterChainExecution(context, chainResult);

            // Then: Key is NOT added (already in cache)
            verify(bloomSupport, never()).add(anyString(), anyString());
        }
    }
}
