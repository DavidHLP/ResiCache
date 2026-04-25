package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.CachedValue;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.ttl.TtlPolicy;
import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshMode;
import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshSupport;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PreRefreshHandler 单元测试
 */
@ExtendWith(MockitoExtension.class)
class PreRefreshHandlerTest {

    @Mock
    private TtlPolicy ttlPolicy;

    @Mock
    private PreRefreshSupport preRefreshSupport;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private CacheStatisticsCollector statistics;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private PreRefreshHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PreRefreshHandler(ttlPolicy, preRefreshSupport, redisTemplate, statistics, valueOperations);
    }

    private CacheContext createContext(CacheOperation operation, RedisCacheableOperation cacheOperation) {
        CacheInput input = new CacheInput(
                operation,
                "test-cache",
                "test:key",
                "testKey",
                null,
                null,
                Duration.ofSeconds(60),
                cacheOperation
        );
        return new CacheContext(input);
    }

    private RedisCacheableOperation createPreRefreshOperation(boolean enablePreRefresh, double threshold, PreRefreshMode mode) {
        return RedisCacheableOperation.builder()
                .name("test-cache")
                .cacheNames("test-cache")
                .enablePreRefresh(enablePreRefresh)
                .preRefreshThreshold(threshold)
                .preRefreshMode(mode)
                .build();
    }

    private CachedValue createCachedValue(long ttlSeconds, long createdTime) {
        return CachedValue.builder()
                .value("test-value")
                .type(String.class)
                .ttl(ttlSeconds)
                .createdTime(createdTime)
                .startNanoTime(System.nanoTime())
                .version(1L)
                .build();
    }

    @Nested
    @DisplayName("shouldHandle tests")
    class ShouldHandleTests {

        @Test
        @DisplayName("returns true for GET with preRefresh enabled")
        void shouldHandle_getWithPreRefreshEnabled_returnsTrue() {
            RedisCacheableOperation operation = createPreRefreshOperation(true, 0.8, PreRefreshMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false for GET with preRefresh disabled")
        void shouldHandle_getWithPreRefreshDisabled_returnsFalse() {
            RedisCacheableOperation operation = createPreRefreshOperation(false, 0.8, PreRefreshMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false for PUT operation even with preRefresh enabled")
        void shouldHandle_putWithPreRefreshEnabled_returnsFalse() {
            RedisCacheableOperation operation = createPreRefreshOperation(true, 0.8, PreRefreshMode.SYNC);
            CacheContext context = createContext(CacheOperation.PUT, operation);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when cache operation is null")
        void shouldHandle_cacheOperationNull_returnsFalse() {
            CacheContext context = createContext(CacheOperation.GET, null);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("doHandle tests - cache miss scenarios")
    class DoHandleCacheMissTests {

        @Test
        @DisplayName("continues chain when cache value is null")
        void doHandle_cacheValueNull_continuesChain() {
            RedisCacheableOperation operation = createPreRefreshOperation(true, 0.8, PreRefreshMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            when(valueOperations.get("test:key")).thenReturn(null);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(ChainDecision.CONTINUE);
            assertThat(result.result()).isNull();
        }

        @Test
        @DisplayName("continues chain when cache value is expired")
        void doHandle_cacheValueExpired_continuesChain() {
            RedisCacheableOperation operation = createPreRefreshOperation(true, 0.8, PreRefreshMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CachedValue cachedValue = CachedValue.builder()
                    .value("test-value")
                    .type(String.class)
                    .ttl(60)
                    .createdTime(System.currentTimeMillis() - 120000)
                    .startNanoTime(System.nanoTime())
                    .version(1L)
                    .expired(true)
                    .build();
            when(valueOperations.get("test:key")).thenReturn(cachedValue);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(ChainDecision.CONTINUE);
        }
    }

    @Nested
    @DisplayName("doHandle tests - no refresh needed scenarios")
    class DoHandleNoRefreshTests {

        @Test
        @DisplayName("continues chain when TTL policy indicates no refresh needed")
        void doHandle_noRefreshNeeded_continuesChain() {
            RedisCacheableOperation operation = createPreRefreshOperation(true, 0.8, PreRefreshMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CachedValue cachedValue = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get("test:key")).thenReturn(cachedValue);
            when(ttlPolicy.shouldPreRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(false);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(ChainDecision.CONTINUE);
            assertThat(result.result()).isNull();
            verify(statistics, never()).incMisses(anyString());
        }
    }

    @Nested
    @DisplayName("doHandle tests - sync refresh scenarios")
    class DoHandleSyncRefreshTests {

        @Test
        @DisplayName("returns skipAll and increments misses when sync refresh needed")
        void doHandle_syncRefreshNeeded_returnsSkipAll() {
            RedisCacheableOperation operation = createPreRefreshOperation(true, 0.8, PreRefreshMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CachedValue cachedValue = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get("test:key")).thenReturn(cachedValue);
            when(ttlPolicy.shouldPreRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(ChainDecision.SKIP_ALL);
            assertThat(context.getAttribute("preRefresh.skipped", false)).isTrue();
            verify(statistics).incMisses("test-cache");
        }

        @Test
        @DisplayName("defaults to SYNC mode when mode is null")
        void doHandle_nullMode_defaultsToSync() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("test-cache")
                    .cacheNames("test-cache")
                    .enablePreRefresh(true)
                    .preRefreshThreshold(0.8)
                    .preRefreshMode(null)
                    .build();
            CacheContext context = createContext(CacheOperation.GET, operation);
            CachedValue cachedValue = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get("test:key")).thenReturn(cachedValue);
            when(ttlPolicy.shouldPreRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(ChainDecision.SKIP_ALL);
            verify(statistics).incMisses("test-cache");
        }
    }

    @Nested
    @DisplayName("doHandle tests - async refresh scenarios")
    class DoHandleAsyncRefreshTests {

        @Test
        @DisplayName("continues chain and schedules async refresh when async mode")
        void doHandle_asyncRefresh_schedulesAndContinues() {
            RedisCacheableOperation operation = createPreRefreshOperation(true, 0.8, PreRefreshMode.ASYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CachedValue cachedValue = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get("test:key")).thenReturn(cachedValue);
            when(ttlPolicy.shouldPreRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(ChainDecision.CONTINUE);
            verify(preRefreshSupport).submitAsyncRefresh(eq("test:key"), any(Runnable.class));
        }

        @Test
        @DisplayName("async refresh does not increment misses")
        void doHandle_asyncRefresh_noMissIncrement() {
            RedisCacheableOperation operation = createPreRefreshOperation(true, 0.8, PreRefreshMode.ASYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CachedValue cachedValue = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get("test:key")).thenReturn(cachedValue);
            when(ttlPolicy.shouldPreRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

            handler.doHandle(context);

            verify(statistics, never()).incMisses(anyString());
        }
    }

    @Nested
    @DisplayName("static getDecision tests")
    class GetDecisionTests {

        @Test
        @DisplayName("returns default noRefresh when attribute not set")
        void getDecision_attributeNotSet_returnsNoRefresh() {
            CacheInput input = new CacheInput(
                    CacheOperation.GET,
                    "test-cache",
                    "test:key",
                    "testKey",
                    null,
                    null,
                    Duration.ofSeconds(60),
                    null
            );
            CacheContext context = new CacheContext(input);

            PreRefreshDecision decision = PreRefreshHandler.getDecision(context);

            assertThat(decision.needsRefresh()).isFalse();
            assertThat(decision.isSync()).isFalse();
        }

        @Test
        @DisplayName("returns stored decision when attribute is set")
        void getDecision_attributeSet_returnsStoredDecision() {
            CacheInput input = new CacheInput(
                    CacheOperation.GET,
                    "test-cache",
                    "test:key",
                    "testKey",
                    null,
                    null,
                    Duration.ofSeconds(60),
                    null
            );
            CacheContext context = new CacheContext(input);
            PreRefreshDecision storedDecision = PreRefreshDecision.syncRefresh();
            context.setAttribute("preRefresh.decision", storedDecision);

            PreRefreshDecision decision = PreRefreshHandler.getDecision(context);

            assertThat(decision).isEqualTo(storedDecision);
        }
    }

    @Nested
    @DisplayName("decision attribute tests")
    class DecisionAttributeTests {

        @Test
        @DisplayName("sets decision attribute in context when refresh needed")
        void doHandle_setsDecisionAttribute() {
            RedisCacheableOperation operation = createPreRefreshOperation(true, 0.8, PreRefreshMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CachedValue cachedValue = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get("test:key")).thenReturn(cachedValue);
            when(ttlPolicy.shouldPreRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

            handler.doHandle(context);

            PreRefreshDecision decision = context.getAttribute("preRefresh.decision");
            assertThat(decision).isNotNull();
            assertThat(decision.needsRefresh()).isTrue();
        }
    }
}