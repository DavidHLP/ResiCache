package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.ttl.TtlPolicy;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * TtlHandler 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TtlHandler Tests")
class TtlHandlerTest {

    @Mock
    private TtlPolicy ttlPolicy;

    @Mock
    private RedisCacheableOperation cacheOperation;

    private TtlHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TtlHandler(ttlPolicy);
    }

    private CacheContext createContext(CacheOperation operation, Duration ttl, RedisCacheableOperation cacheOp) {
        CacheInput input = new CacheInput(
                operation,
                "test-cache",
                "test:key",
                "key",
                new byte[]{1},
                "value",
                ttl,
                cacheOp
        );
        return new CacheContext(input);
    }

    @Nested
    @DisplayName("shouldHandle")
    class ShouldHandleTests {

        @Test
        @DisplayName("returns true for PUT operation")
        void shouldHandle_putOperation_returnsTrue() {
            CacheContext context = createContext(CacheOperation.PUT, null, null);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns true for PUT_IF_ABSENT operation")
        void shouldHandle_putIfAbsentOperation_returnsTrue() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT, null, null);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false for GET operation")
        void shouldHandle_getOperation_returnsFalse() {
            CacheContext context = createContext(CacheOperation.GET, null, null);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false for REMOVE operation")
        void shouldHandle_removeOperation_returnsFalse() {
            CacheContext context = createContext(CacheOperation.REMOVE, null, null);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false for CLEAN operation")
        void shouldHandle_cleanOperation_returnsFalse() {
            CacheContext context = createContext(CacheOperation.CLEAN, null, null);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("doHandle - TTL from cache operation config")
    class TtlFromConfigTests {

        @Test
        @DisplayName("uses cache operation TTL when configured")
        void doHandle_withCacheOperationTtl_usesConfigTtl() {
            when(cacheOperation.getTtl()).thenReturn(120L);
            when(cacheOperation.isRandomTtl()).thenReturn(true);
            when(cacheOperation.getVariance()).thenReturn(0.1f);
            when(ttlPolicy.calculateFinalTtl(eq(120L), eq(true), eq(0.1f))).thenReturn(130L);
            CacheContext context = createContext(CacheOperation.PUT, null, cacheOperation);

            handler.doHandle(context);

            assertThat(context.isShouldApplyTtl()).isTrue();
            assertThat(context.getFinalTtl()).isEqualTo(130L);
            assertThat(context.isTtlFromContext()).isTrue();
        }

        @Test
        @DisplayName("sets shouldApplyTtl true when cache operation TTL is positive")
        void doHandle_positiveTtl_setsShouldApplyTtlTrue() {
            when(cacheOperation.getTtl()).thenReturn(60L);
            when(ttlPolicy.calculateFinalTtl(anyLong(), anyBoolean(), anyFloat())).thenReturn(60L);
            CacheContext context = createContext(CacheOperation.PUT, null, cacheOperation);

            handler.doHandle(context);

            assertThat(context.isShouldApplyTtl()).isTrue();
        }

        @Test
        @DisplayName("does not use cache operation TTL when TTL is zero or negative")
        void doHandle_zeroTtl_doesNotUseConfigTtl() {
            when(cacheOperation.getTtl()).thenReturn(0L);
            CacheContext context = createContext(CacheOperation.PUT, Duration.ofSeconds(30), cacheOperation);
            when(ttlPolicy.shouldApply(any(Duration.class))).thenReturn(true);

            handler.doHandle(context);

            // Should fall through to use parameter TTL instead
            assertThat(context.isTtlFromContext()).isFalse();
        }
    }

    @Nested
    @DisplayName("doHandle - TTL from parameter")
    class TtlFromParameterTests {

        @Test
        @DisplayName("uses parameter TTL when cache operation TTL is not set")
        void doHandle_noConfigTtl_usesParameterTtl() {
            when(cacheOperation.getTtl()).thenReturn(0L);
            when(ttlPolicy.shouldApply(Duration.ofSeconds(30))).thenReturn(true);
            CacheContext context = createContext(CacheOperation.PUT, Duration.ofSeconds(30), cacheOperation);

            handler.doHandle(context);

            assertThat(context.isShouldApplyTtl()).isTrue();
            assertThat(context.getFinalTtl()).isEqualTo(30L);
            assertThat(context.isTtlFromContext()).isFalse();
        }

        @Test
        @DisplayName("sets shouldApplyTtl true when ttl policy says apply")
        void doHandle_ttlPolicySaysApply_setsShouldApplyTtlTrue() {
            when(cacheOperation.getTtl()).thenReturn(0L);
            when(ttlPolicy.shouldApply(Duration.ofSeconds(60))).thenReturn(true);
            CacheContext context = createContext(CacheOperation.PUT, Duration.ofSeconds(60), cacheOperation);

            handler.doHandle(context);

            assertThat(context.isShouldApplyTtl()).isTrue();
        }
    }

    @Nested
    @DisplayName("doHandle - No TTL (permanent cache)")
    class NoTtlTests {

        @Test
        @DisplayName("sets no TTL when ttl policy says do not apply")
        void doHandle_ttlPolicySaysDoNotApply_setsNoTtl() {
            when(cacheOperation.getTtl()).thenReturn(0L);
            when(ttlPolicy.shouldApply(Duration.ofSeconds(-1))).thenReturn(false);
            CacheContext context = createContext(CacheOperation.PUT, Duration.ofSeconds(-1), cacheOperation);

            handler.doHandle(context);

            assertThat(context.isShouldApplyTtl()).isFalse();
            assertThat(context.getFinalTtl()).isEqualTo(-1L);
            assertThat(context.isTtlFromContext()).isFalse();
        }

        @Test
        @DisplayName("sets no TTL when both config and parameter TTL are not available")
        void doHandle_noTtlConfigured_setsNoTtl() {
            when(cacheOperation.getTtl()).thenReturn(0L);
            when(ttlPolicy.shouldApply(any(Duration.class))).thenReturn(false);
            CacheContext context = createContext(CacheOperation.PUT, null, cacheOperation);

            handler.doHandle(context);

            assertThat(context.isShouldApplyTtl()).isFalse();
            assertThat(context.getFinalTtl()).isEqualTo(-1L);
        }
    }

    @Nested
    @DisplayName("doHandle - Default TTL")
    class DefaultTtlTests {

        @Test
        @DisplayName("uses default TTL when no TTL is provided")
        void doHandle_noTtlProvided_usesDefaultTtl() {
            when(cacheOperation.getTtl()).thenReturn(0L);
            when(ttlPolicy.shouldApply(Duration.ofSeconds(60))).thenReturn(true);
            CacheContext context = createContext(CacheOperation.PUT, null, cacheOperation);

            handler.doHandle(context);

            // Default TTL is 60 seconds
            assertThat(context.isShouldApplyTtl()).isTrue();
            assertThat(context.getFinalTtl()).isEqualTo(60L);
            assertThat(context.isTtlFromContext()).isFalse();
        }
    }

    @Nested
    @DisplayName("doHandle - Chain continuation")
    class ChainContinuationTests {

        @Test
        @DisplayName("always continues chain after TTL calculation")
        void doHandle_alwaysContinuesChain() {
            when(cacheOperation.getTtl()).thenReturn(120L);
            when(ttlPolicy.calculateFinalTtl(anyLong(), anyBoolean(), anyFloat())).thenReturn(120L);
            CacheContext context = createContext(CacheOperation.PUT, null, cacheOperation);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isFalse();
        }
    }
}
