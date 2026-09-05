package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.FlowControl;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.EarlyExpirationDecision;
import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * EarlyExpirationHandler tests backed by a real Redis container.
 *
 * <p>The policy mock is intentional: it controls the refresh decision, not Redis I/O.
 * Redis TTL reads and cached-value reads/writes all use the real integration beans.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EarlyExpirationHandler Tests (real Redis)")
class EarlyExpirationHandlerIntegrationTest extends AbstractRedisIntegrationTest {

    private static final String REDIS_KEY = "test:key";
    private static final String CACHE_NAME = "test-cache";

    @Mock
    private EarlyExpirationPolicy earlyExpirationPolicy;

    @Mock
    private ThreadPoolEarlyExpirationExecutor earlyExpirationExecutor;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private CacheStatisticsCollector statistics;

    @Autowired
    private ValueOperations<String, Object> valueOperations;

    private EarlyExpirationHandler handler;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushDb();
        handler = new EarlyExpirationHandler(
                earlyExpirationPolicy,
                earlyExpirationExecutor,
                redisTemplate,
                statistics,
                valueOperations);
    }

    private CacheContext createContext(CacheOperation operation, RedisCacheableOperation cacheOperation) {
        CacheInput input = new CacheInput(
                operation,
                CACHE_NAME,
                REDIS_KEY,
                "testKey",
                null,
                null,
                Duration.ofSeconds(60),
                cacheOperation
        );
        return new CacheContext(input);
    }

    private RedisCacheableOperation createEarlyExpirationOperation(
            boolean enableEarlyExpiration, double threshold, EarlyExpirationMode mode) {
        return RedisCacheableOperation.builder()
                .name(CACHE_NAME)
                .cacheNames(CACHE_NAME)
                .enableEarlyExpiration(enableEarlyExpiration)
                .earlyExpirationThreshold(threshold)
                .earlyExpirationMode(mode)
                .build();
    }

    private CachedValue createCachedValue(long ttlSeconds, long createdTime) {
        return createCachedValue("test-value", ttlSeconds, createdTime, 1L, false);
    }

    private CachedValue createCachedValue(long ttlSeconds, long createdTime, long version, boolean expired) {
        return createCachedValue("test-value", ttlSeconds, createdTime, version, expired);
    }

    private CachedValue createCachedValue(String value, long ttlSeconds, long createdTime,
                                          long version, boolean expired) {
        return CachedValue.forTest(value, ttlSeconds, createdTime, version, expired);
    }

    private void store(CachedValue value, long ttlSeconds) {
        valueOperations.set(REDIS_KEY, value, Duration.ofSeconds(ttlSeconds));
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
        @DisplayName("continues chain when the real Redis value is null")
        void doHandle_cacheValueNull_continuesChain() {
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            valueOperations.set(REDIS_KEY, null, Duration.ofSeconds(30));

            HandlerResult result = handler.doHandle(context);

            assertThat(redisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS)).isBetween(1L, 30L);
            assertThat(result.decision()).isEqualTo(FlowControl.CONTINUE);
            assertThat(result.result()).isNull();
            verifyNoInteractions(earlyExpirationPolicy);
        }

        @Test
        @DisplayName("continues chain when real remaining TTL > fast-path threshold")
        void doHandle_fastPath_skipsGet() {
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            store(createCachedValue(120, System.currentTimeMillis()), 120);

            HandlerResult result = handler.doHandle(context);

            assertThat(redisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS)).isGreaterThan(60L);
            assertThat(result.decision()).isEqualTo(FlowControl.CONTINUE);
            // Fast path must not evaluate the policy or inspect the cached value.
            verifyNoInteractions(earlyExpirationPolicy);
        }

        @Test
        @DisplayName("continues chain when the real Redis key is absent")
        void doHandle_fastPath_ttlMissing_continuesChain() {
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);

            HandlerResult result = handler.doHandle(context);

            assertThat(redisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS)).isEqualTo(-2L);
            assertThat(result.decision()).isEqualTo(FlowControl.CONTINUE);
            verifyNoInteractions(earlyExpirationPolicy);
        }

        @Test
        @DisplayName("continues chain when the real cached value is expired")
        void doHandle_cacheValueExpired_continuesChain() {
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            store(createCachedValue(60, System.currentTimeMillis(), 1L, true), 30);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(FlowControl.CONTINUE);
        }
    }

    @Nested
    @DisplayName("doHandle tests - no refresh needed scenarios")
    class DoHandleNoRefreshTests {

        @Test
        @DisplayName("continues chain when policy indicates no refresh")
        void doHandle_noRefreshNeeded_continuesChain() {
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            store(createCachedValue(60, System.currentTimeMillis()), 30);
            // Policy is a non-Redis decision collaborator; keep it mocked deliberately.
            when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(false);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(FlowControl.CONTINUE);
            assertThat(result.result()).isNull();
            assertThat(context.getPrefetchDecision().decision().needsRefresh()).isFalse();
        }
    }

    @Nested
    @DisplayName("doHandle tests - sync refresh scenarios")
    class DoHandleSyncRefreshTests {

        @Test
        @DisplayName("returns skipAll when real TTL is in the refresh window")
        void doHandle_syncRefreshNeeded_returnsSkipAll() {
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.SYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            store(createCachedValue(60, System.currentTimeMillis()), 30);
            // Policy controls only the refresh branch; all Redis reads above are real.
            when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(FlowControl.SKIP_ALL);
            assertThat(context.getPrefetchDecision().earlyExpirationSkipped()).isTrue();
            assertThat(context.getPrefetchDecision().decision().needsRefresh()).isTrue();
        }

        @Test
        @DisplayName("defaults to SYNC mode when mode is null")
        void doHandle_nullMode_defaultsToSync() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name(CACHE_NAME)
                    .cacheNames(CACHE_NAME)
                    .enableEarlyExpiration(true)
                    .earlyExpirationThreshold(0.8)
                    .earlyExpirationMode(null)
                    .build();
            CacheContext context = createContext(CacheOperation.GET, operation);
            store(createCachedValue(60, System.currentTimeMillis()), 30);
            when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(FlowControl.SKIP_ALL);
            assertThat(context.getPrefetchDecision().earlyExpirationSkipped()).isTrue();
        }
    }

    @Nested
    @DisplayName("doHandle tests - async refresh scenarios")
    class DoHandleAsyncRefreshTests {

        @Test
        @DisplayName("continues chain and schedules async refresh in real Redis")
        void doHandle_asyncRefresh_schedulesAndContinues() {
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.ASYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            store(createCachedValue(60, System.currentTimeMillis()), 30);
            when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(FlowControl.CONTINUE);
            verify(earlyExpirationExecutor).submit(any(String.class), any(Runnable.class));
        }

        @Test
        @DisplayName("async refresh does not skip the real Redis read")
        void doHandle_asyncRefresh_noMissIncrement() {
            RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.ASYNC);
            CacheContext context = createContext(CacheOperation.GET, operation);
            store(createCachedValue(60, System.currentTimeMillis()), 30);
            when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(FlowControl.CONTINUE);
            assertThat(context.getPrefetchDecision().decision().isSync()).isFalse();
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
            store(createCachedValue(60, System.currentTimeMillis()), 30);
            when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

            handler.doHandle(context);

            EarlyExpirationDecision decision = context.getPrefetchDecision().decision();
            assertThat(decision).isNotNull();
            assertThat(decision.needsRefresh()).isTrue();
        }
    }

    /**
     * performAsyncRefresh tests use real Redis for the normal value/TTL/CAS paths.
     * Only the two exception tests construct a separate handler with mocked I/O,
     * because real Redis cannot deterministically inject those failures.
     */
    @Nested
    @DisplayName("performAsyncRefresh tests")
    class PerformAsyncRefreshTests {

        @Test
        @DisplayName("returns early when live value is null")
        void performAsyncRefresh_liveValueNull_returnsEarly() {
            CachedValue captured = createCachedValue(60, System.currentTimeMillis());

            handler.performAsyncRefresh(REDIS_KEY, CACHE_NAME, captured);

            assertThat(redisTemplate.hasKey(REDIS_KEY)).isFalse();
        }

        @Test
        @DisplayName("returns early when live value is below the grace period")
        void performAsyncRefresh_belowGracePeriod_returnsEarly() {
            CachedValue captured = createCachedValue(60, System.currentTimeMillis());
            CachedValue live = CachedValue.forTest("v", 2L, System.currentTimeMillis(), 1L, false);
            store(live, 30);

            handler.performAsyncRefresh(REDIS_KEY, CACHE_NAME, captured);

            assertThat(redisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS)).isGreaterThan(5L);
        }

        @Test
        @DisplayName("runs the Lua CAS path against real Redis without corrupting TTL")
        void performAsyncRefresh_casPath_preservesTtl() {
            // The real serializer controls the wire representation; this test
            // asserts the Lua CAS shortened the native TTL to the grace period.
            // captured 与 live 同 version(2L)→ CAS 比对通过 → Lua 把 Redis TTL 收缩到
            // REFRESH_GRACE_PERIOD_SECONDS(5s)。断言用范围而非精确 5L:真实 Redis 在 Lua
            // 收缩(getExpire 读到 5)与断言读回之间会流逝约 1 秒(读到 4),原 mock 测试
            // 无法暴露此实时消耗。范围 (0, 5] 既证明 CAS 生效(从 30s 收缩到 ≤5s)又容差时钟。
            CachedValue captured = createCachedValue(60, System.currentTimeMillis(), 2L, false);
            CachedValue live = createCachedValue(60, System.currentTimeMillis(), 2L, false);
            store(live, 30);

            handler.performAsyncRefresh(REDIS_KEY, CACHE_NAME, captured);

            assertThat(redisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS))
                    .isBetween(1L, 5L);
        }

        @Test
        @DisplayName("does not shorten real Redis TTL when the value changed")
        void performAsyncRefresh_casReturnsFalse_valueChangedPath() {
            CachedValue captured = createCachedValue("captured", 60, System.currentTimeMillis(), 1L, false);
            CachedValue live = createCachedValue("live", 60, System.currentTimeMillis(), 2L, false);
            store(live, 30);

            handler.performAsyncRefresh(REDIS_KEY, CACHE_NAME, captured);

            assertThat(redisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS)).isGreaterThan(5L);
            assertThat(((CachedValue) valueOperations.get(REDIS_KEY)).getValue()).isEqualTo("live");
        }

        @Test
        @DisplayName("catches a value-fetch exception from a mocked I/O seam")
        void performAsyncRefresh_valueFetchThrows_catchesAndLogs() {
            CachedValue captured = createCachedValue(60, System.currentTimeMillis());
            RedisTemplate<String, Object> mockedRedisTemplate = mock(RedisTemplate.class);
            ValueOperations<String, Object> mockedValueOperations = mock(ValueOperations.class);
            when(mockedValueOperations.get(REDIS_KEY)).thenThrow(new RuntimeException("Redis down"));
            EarlyExpirationHandler faultHandler = new EarlyExpirationHandler(
                    earlyExpirationPolicy,
                    earlyExpirationExecutor,
                    mockedRedisTemplate,
                    mock(CacheStatisticsCollector.class),
                    mockedValueOperations);

            faultHandler.performAsyncRefresh(REDIS_KEY, CACHE_NAME, captured);

            verify(mockedRedisTemplate, never()).execute(any(RedisCallback.class));
        }

        @Test
        @DisplayName("catches a Lua CAS exception from a mocked I/O seam")
        void performAsyncRefresh_casThrows_catchesAndLogs() {
            CachedValue captured = createCachedValue(60, System.currentTimeMillis());
            CachedValue live = createCachedValue(60, System.currentTimeMillis());
            RedisTemplate<String, Object> mockedRedisTemplate = mock(RedisTemplate.class);
            ValueOperations<String, Object> mockedValueOperations = mock(ValueOperations.class);
            when(mockedValueOperations.get(REDIS_KEY)).thenReturn(live);
            when(mockedRedisTemplate.execute(any(RedisCallback.class)))
                    .thenThrow(new RuntimeException("Lua eval failed"));
            EarlyExpirationHandler faultHandler = new EarlyExpirationHandler(
                    earlyExpirationPolicy,
                    earlyExpirationExecutor,
                    mockedRedisTemplate,
                    mock(CacheStatisticsCollector.class),
                    mockedValueOperations);

            faultHandler.performAsyncRefresh(REDIS_KEY, CACHE_NAME, captured);

            verify(mockedRedisTemplate).execute(any(RedisCallback.class));
        }
    }
}
