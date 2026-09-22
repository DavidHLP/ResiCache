package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TtlHandler 单元测试。
 *
 * <p>TTL 优先级、默认值与抖动由 {@link TtlPolicy} 拥有(见 TtlPolicyTest);
 * 本测试覆盖处理器对决策的应用与 jitter 计数。
 */
@DisplayName("TtlHandler Tests")
class TtlHandlerTest {

    private TtlHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TtlHandler();
    }

    private CacheContext createContext(CacheOperation operation, Duration ttl,
                                       RedisCacheableOperation cacheOperation) {
        CacheInput input = new CacheInput(
                operation,
                "test-cache",
                "test:key",
                "key",
                new byte[]{1},
                "value",
                ttl,
                cacheOperation
        );
        return new CacheContext(input);
    }

    private RedisCacheableOperation annotatedOperation(long ttl, boolean randomTtl, float variance) {
        return RedisCacheableOperation.builder()
                .name("test-cache")
                .cacheNames("test-cache")
                .ttl(ttl)
                .randomTtl(randomTtl)
                .variance(variance)
                .build();
    }

    @Nested
    @DisplayName("ttl.jittered counter")
    class TtlJitteredCounterTests {

        @Test
        @DisplayName("randomTtl=true increments the jitter counter")
        void randomTtlTrue_incrementsJitteredCounter() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            TtlHandler h = new TtlHandler();
            h.attachMeterRegistry(registry);

            h.handle(createContext(CacheOperation.PUT, Duration.ofSeconds(60),
                    annotatedOperation(60, true, 0.2f)));

            assertThat(registry.get("resicache.handler.ttl.jittered").counter().count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("randomTtl=false does not increment the jitter counter")
        void randomTtlFalse_doesNotIncrement() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            TtlHandler h = new TtlHandler();
            h.attachMeterRegistry(registry);

            h.handle(createContext(CacheOperation.PUT, Duration.ofSeconds(60),
                    annotatedOperation(60, false, 0.2f)));

            assertThat(registry.get("resicache.handler.ttl.jittered").counter().count())
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("randomTtl=true on the parameter path does not increment the jitter counter")
        void randomTtlTrue_parameterPath_doesNotIncrement() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            TtlHandler h = new TtlHandler();
            h.attachMeterRegistry(registry);

            h.handle(createContext(CacheOperation.PUT, Duration.ofSeconds(60),
                    annotatedOperation(0, true, 0.2f)));

            assertThat(registry.get("resicache.handler.ttl.jittered").counter().count())
                    .isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("shouldHandle")
    class ShouldHandleTests {

        @Test
        void shouldHandle_putOperation_returnsTrue() {
            assertThat(handler.shouldHandle(createContext(CacheOperation.PUT, null, null))).isTrue();
        }

        @Test
        void shouldHandle_putIfAbsentOperation_returnsTrue() {
            assertThat(handler.shouldHandle(
                    createContext(CacheOperation.PUT_IF_ABSENT, null, null))).isTrue();
        }

        @Test
        void shouldHandle_getOperation_returnsFalse() {
            assertThat(handler.shouldHandle(createContext(CacheOperation.GET, null, null))).isFalse();
        }

        @Test
        void shouldHandle_removeOperation_returnsFalse() {
            assertThat(handler.shouldHandle(createContext(CacheOperation.REMOVE, null, null))).isFalse();
        }

        @Test
        void shouldHandle_cleanOperation_returnsFalse() {
            assertThat(handler.shouldHandle(createContext(CacheOperation.CLEAN, null, null))).isFalse();
        }
    }

    @Nested
    @DisplayName("handler-applied TTL decisions")
    class TtlDecisionTests {

        @Test
        void annotationTtl_takesPrecedenceOverParameterTtl() {
            CacheContext context = createContext(CacheOperation.PUT, Duration.ofSeconds(30),
                    annotatedOperation(120, false, 0.2f));

            handler.doHandle(context, CacheResult::success);

            assertThat(context.getTtlDecision().shouldApplyTtl()).isTrue();
            assertThat(context.getTtlDecision().finalTtl()).isEqualTo(120L);
        }

        @Test
        void parameterTtl_isUsedWhenAnnotationTtlIsZero() {
            CacheContext context = createContext(CacheOperation.PUT, Duration.ofSeconds(30),
                    annotatedOperation(0, false, 0.2f));

            handler.doHandle(context, CacheResult::success);

            assertThat(context.getTtlDecision().shouldApplyTtl()).isTrue();
            assertThat(context.getTtlDecision().finalTtl()).isEqualTo(30L);
        }

        @Test
        void zeroParameterTtl_skipsTtl() {
            CacheContext context = createContext(CacheOperation.PUT, Duration.ZERO,
                    annotatedOperation(0, false, 0.2f));

            handler.doHandle(context, CacheResult::success);

            assertThat(context.getTtlDecision().shouldApplyTtl()).isFalse();
            assertThat(context.getTtlDecision().finalTtl()).isEqualTo(-1L);
        }

        @Test
        void negativeParameterTtl_mapsToPermanentCacheSentinel() {
            CacheContext context = createContext(CacheOperation.PUT, Duration.ofSeconds(-1),
                    annotatedOperation(0, false, 0.2f));

            handler.doHandle(context, CacheResult::success);

            assertThat(context.getTtlDecision().shouldApplyTtl()).isFalse();
            assertThat(context.getTtlDecision().finalTtl()).isEqualTo(-1L);
        }

        @Test
        void missingTtl_usesDefaultTtl() {
            CacheContext context = createContext(CacheOperation.PUT, null,
                    annotatedOperation(0, false, 0.2f));

            handler.doHandle(context, CacheResult::success);

            assertThat(context.getTtlDecision().shouldApplyTtl()).isTrue();
            assertThat(context.getTtlDecision().finalTtl()).isEqualTo(60L);
        }
    }

    @Test
    void doHandle_alwaysContinuesChain() {
        CacheContext context = createContext(CacheOperation.PUT, null,
                annotatedOperation(120, false, 0.2f));

        HandlerResult result = handler.doHandle(context, CacheResult::success);

        assertThat(result.shouldTerminate()).isFalse();
    }
}
