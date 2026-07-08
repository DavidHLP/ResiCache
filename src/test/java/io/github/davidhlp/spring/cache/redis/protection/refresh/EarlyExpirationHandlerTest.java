package io.github.davidhlp.spring.cache.redis.protection.refresh;

import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.model.*;


import io.github.davidhlp.spring.cache.redis.cache.CachedValue;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
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
 * EarlyExpirationHandler 单元测试
 */
@ExtendWith(MockitoExtension.class)
class EarlyExpirationHandlerTest {

    @Mock
    private EarlyExpirationPolicy earlyExpirationPolicy;

    @Mock
    private ThreadPoolEarlyExpirationExecutor earlyExpirationExecutor;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private CacheStatisticsCollector statistics;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private EarlyExpirationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new EarlyExpirationHandler(earlyExpirationPolicy, earlyExpirationExecutor, redisTemplate, statistics, valueOperations);
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

    private RedisCacheableOperation createEarlyExpirationOperation(boolean enableEarlyExpiration, double threshold, EarlyExpirationMode mode) {
        return RedisCacheableOperation.builder()
                .name("test-cache")
                .cacheNames("test-cache")
                .enableEarlyExpiration(enableEarlyExpiration)
                .earlyExpirationThreshold(threshold)
                .earlyExpirationMode(mode)
                .build();
    }

    private CachedValue createCachedValue(long ttlSeconds, long createdTime) {
        return CachedValue.forTest("test-value", ttlSeconds, createdTime, 1L, false);
    }

    @Nested
    @DisplayName("shouldHandle tests")
    class ShouldHandleTests {

        @Test
        @DisplayName("returns true for GET with earlyExpiration enabled")
        void shouldHandle_getWithEarlyExpirationEnabled_returnsTrue() {
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false for GET with earlyExpiration disabled")
        void shouldHandle_getWithEarlyExpirationDisabled_returnsFalse() {
            RedisCacheableOperation operation = createEarlyExpirationOperation(false, 0.8, EarlyExpirationMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false for PUT operation even with earlyExpiration enabled")
        void shouldHandle_putWithEarlyExpirationEnabled_returnsFalse() {
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.SYNC);
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
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            when(valueOperations.get("test:key")).thenReturn(null);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(ChainDecision.CONTINUE);
            assertThat(result.result()).isNull();
        }

        @Test
        @DisplayName("continues chain when cache value is expired")
        void doHandle_cacheValueExpired_continuesChain() {
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CachedValue cachedValue = CachedValue.forTest(
                    "test-value",
                    60,
                    System.currentTimeMillis() - 120000,
                    1L,
                    true);
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
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CachedValue cachedValue = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get("test:key")).thenReturn(cachedValue);
            when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(false);

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
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CachedValue cachedValue = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get("test:key")).thenReturn(cachedValue);
            when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(ChainDecision.SKIP_ALL);
            assertThat(context.getPrefetchDecision().earlyExpirationSkipped()).isTrue();
            verify(statistics).incMisses("test-cache");
        }

        @Test
        @DisplayName("defaults to SYNC mode when mode is null")
        void doHandle_nullMode_defaultsToSync() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("test-cache")
                    .cacheNames("test-cache")
                    .enableEarlyExpiration(true)
                    .earlyExpirationThreshold(0.8)
                    .earlyExpirationMode(null)
                    .build();
            CacheContext context = createContext(CacheOperation.GET, operation);
            CachedValue cachedValue = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get("test:key")).thenReturn(cachedValue);
            when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

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
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.ASYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CachedValue cachedValue = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get("test:key")).thenReturn(cachedValue);
            when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(ChainDecision.CONTINUE);
            verify(earlyExpirationExecutor).submit(eq("test:key"), any(Runnable.class));
        }

        @Test
        @DisplayName("async refresh does not increment misses")
        void doHandle_asyncRefresh_noMissIncrement() {
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.ASYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CachedValue cachedValue = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get("test:key")).thenReturn(cachedValue);
            when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

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

            EarlyExpirationDecision decision = EarlyExpirationHandler.getDecision(context);

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
            EarlyExpirationDecision storedDecision = EarlyExpirationDecision.syncRefresh();
            context.setPrefetchDecision(PrefetchDecision.of(false, null, storedDecision));

            EarlyExpirationDecision decision = EarlyExpirationHandler.getDecision(context);

            assertThat(decision).isEqualTo(storedDecision);
        }
    }

    @Nested
    @DisplayName("decision attribute tests")
    class DecisionAttributeTests {

        @Test
        @DisplayName("sets decision attribute in context when refresh needed")
        void doHandle_setsDecisionAttribute() {
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CachedValue cachedValue = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get("test:key")).thenReturn(cachedValue);
            when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

            handler.doHandle(context);

            EarlyExpirationDecision decision = context.getPrefetchDecision().decision();
            assertThat(decision).isNotNull();
            assertThat(decision.needsRefresh()).isTrue();
        }
    }

    /**
     * ADR-0057 抽出的 performAsyncRefresh 单测 — 覆盖原 22 行内联 lambda 的 3 决策分支
     * + 异常翻译。直接调方法,绕过 executor 调度,验证纯逻辑。
     */
    @Nested
    @DisplayName("performAsyncRefresh tests — ADR-0057 async task seam")
    class PerformAsyncRefreshTests {

        private static final String REDIS_KEY = "test:key";
        private static final String CACHE_NAME = "test-cache";

        @Test
        @DisplayName("returns early when live value is null (key already missing)")
        void performAsyncRefresh_liveValueNull_returnsEarly() {
            CachedValue captured = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get(REDIS_KEY)).thenReturn(null);

            handler.performAsyncRefresh(REDIS_KEY, CACHE_NAME, captured);

            // 不应触发 Lua CAS 路径(否则会调 redisTemplate.execute)
            verify(redisTemplate, never()).execute(any(org.springframework.data.redis.core.RedisCallback.class));
        }

        @Test
        @DisplayName("returns early when remainingTtl is below grace period")
        void performAsyncRefresh_belowGracePeriod_returnsEarly() {
            CachedValue captured = createCachedValue(60, System.currentTimeMillis());
            // remainingTtl = 2s,小于 REFRESH_GRACE_PERIOD_SECONDS = 5
            CachedValue live = CachedValue.forTest("v", 2L, System.currentTimeMillis(), 1L, false);
            when(valueOperations.get(REDIS_KEY)).thenReturn(live);

            handler.performAsyncRefresh(REDIS_KEY, CACHE_NAME, captured);

            // 仍在宽限期内 → 不调 Lua CAS
            verify(redisTemplate, never()).execute(any(org.springframework.data.redis.core.RedisCallback.class));
        }

        @Test
        @DisplayName("calls Lua CAS when remainingTtl >= grace period and CAS succeeds")
        void performAsyncRefresh_casSucceeds_callsShorten() {
            CachedValue captured = createCachedValue(60, System.currentTimeMillis());
            // remainingTtl = 60s,大于 GRACE
            CachedValue live = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get(REDIS_KEY)).thenReturn(live);
            when(redisTemplate.execute(any(org.springframework.data.redis.core.RedisCallback.class)))
                    .thenReturn(Boolean.TRUE);

            handler.performAsyncRefresh(REDIS_KEY, CACHE_NAME, captured);

            // 触发 CAS
            verify(redisTemplate).execute(any(org.springframework.data.redis.core.RedisCallback.class));
        }

        @Test
        @DisplayName("calls Lua CAS when remainingTtl >= grace period and CAS returns false (value changed)")
        void performAsyncRefresh_casReturnsFalse_valueChangedPath() {
            CachedValue captured = createCachedValue(60, System.currentTimeMillis());
            CachedValue live = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get(REDIS_KEY)).thenReturn(live);
            // CAS 返回 false 表示 value 已被并发修改
            when(redisTemplate.execute(any(org.springframework.data.redis.core.RedisCallback.class)))
                    .thenReturn(Boolean.FALSE);

            // 不应抛异常 — value-changed 是正常分支
            handler.performAsyncRefresh(REDIS_KEY, CACHE_NAME, captured);

            verify(redisTemplate).execute(any(org.springframework.data.redis.core.RedisCallback.class));
        }

        @Test
        @DisplayName("exception during value fetch is caught and logged, not propagated")
        void performAsyncRefresh_valueFetchThrows_catchesAndLogs() {
            CachedValue captured = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get(REDIS_KEY)).thenThrow(new RuntimeException("Redis down"));

            // 不应向上抛 — 异常已被方法体 try/catch 吞咽
            handler.performAsyncRefresh(REDIS_KEY, CACHE_NAME, captured);

            // Lua CAS 不应被调用(因为 value fetch 阶段就抛了)
            verify(redisTemplate, never()).execute(any(org.springframework.data.redis.core.RedisCallback.class));
        }

        @Test
        @DisplayName("exception during Lua CAS is caught and logged, not propagated")
        void performAsyncRefresh_casThrows_catchesAndLogs() {
            CachedValue captured = createCachedValue(60, System.currentTimeMillis());
            CachedValue live = createCachedValue(60, System.currentTimeMillis());
            when(valueOperations.get(REDIS_KEY)).thenReturn(live);
            when(redisTemplate.execute(any(org.springframework.data.redis.core.RedisCallback.class)))
                    .thenThrow(new RuntimeException("Lua eval failed"));

            // 不应向上抛
            handler.performAsyncRefresh(REDIS_KEY, CACHE_NAME, captured);

            verify(redisTemplate).execute(any(org.springframework.data.redis.core.RedisCallback.class));
        }
    }
}