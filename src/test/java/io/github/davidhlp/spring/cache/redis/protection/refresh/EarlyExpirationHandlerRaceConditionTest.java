package io.github.davidhlp.spring.cache.redis.protection.refresh;

import io.github.davidhlp.spring.cache.redis.cache.CachedValue;
import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.model.*;
import io.github.davidhlp.spring.cache.redis.integration.AbstractRedisIntegrationTest;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EarlyExpirationHandler race tests backed by real Redis state.
 *
 * <p>The policy and executor remain mocks because they control the refresh decision
 * and scheduling seam. Redis reads and writes are always performed by the real
 * integration beans; in particular, concurrent phases use real SET/DELETE calls.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EarlyExpirationHandler Race Condition Tests (real Redis)")
class EarlyExpirationHandlerRaceConditionTest extends AbstractRedisIntegrationTest {

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
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushDb();
        handler = new EarlyExpirationHandler(
                earlyExpirationPolicy,
                earlyExpirationExecutor,
                redisTemplate,
                statistics,
                valueOperations);
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
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

    private CachedValue createCachedValue(long ttlSeconds, long createdTime, long version) {
        return createCachedValue("test-value", ttlSeconds, createdTime, version);
    }

    private CachedValue createCachedValue(String value, long ttlSeconds, long createdTime, long version) {
        return CachedValue.forTest(value, ttlSeconds, createdTime, version, false);
    }

    private void store(CachedValue value, long ttlSeconds) {
        valueOperations.set(REDIS_KEY, value, Duration.ofSeconds(ttlSeconds));
    }

    @Test
    @DisplayName("async refresh and eviction do not corrupt real Redis state")
    void asyncRefreshAndEvict_concurrentNoCorruption() throws InterruptedException {
        RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.ASYNC);
        CacheContext context = createContext(CacheOperation.GET, operation);
        CachedValue cachedValue = createCachedValue(60, System.currentTimeMillis(), 1L);
        AtomicBoolean exceptionThrown = new AtomicBoolean(false);
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch allowRefresh = new CountDownLatch(1);
        CountDownLatch refreshFinished = new CountDownLatch(1);
        CountDownLatch evictFinished = new CountDownLatch(1);

        store(cachedValue, 30);
        when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(1);
            executor.submit(() -> {
                try {
                    refreshStarted.countDown();
                    allowRefresh.await(5, TimeUnit.SECONDS);
                    runnable.run();
                } catch (Exception e) {
                    exceptionThrown.set(true);
                } finally {
                    refreshFinished.countDown();
                }
            });
            return null;
        }).when(earlyExpirationExecutor).submit(eq(REDIS_KEY), any(Runnable.class));

        // First call observes the real TTL/value and schedules the refresh.
        handler.doHandle(context);
        assertThat(refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // Evict the real Redis key while the refresh task is paused.
        executor.submit(() -> {
            try {
                redisTemplate.delete(REDIS_KEY);
            } finally {
                evictFinished.countDown();
            }
        });

        assertThat(evictFinished.await(5, TimeUnit.SECONDS)).isTrue();
        allowRefresh.countDown();
        assertThat(refreshFinished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(exceptionThrown.get()).isFalse();
        assertThat(redisTemplate.hasKey(REDIS_KEY)).isFalse();
        verify(earlyExpirationExecutor).submit(eq(REDIS_KEY), any(Runnable.class));
    }

    @Test
    @DisplayName("async refresh observes a concurrent real Redis write")
    void asyncRefreshAndPut_concurrentCorrectPrecedence() throws InterruptedException {
        RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.ASYNC);
        CacheContext context = createContext(CacheOperation.GET, operation);
        CachedValue originalValue = createCachedValue("original", 60, System.currentTimeMillis(), 1L);
        CachedValue newValue = createCachedValue("new", 60, System.currentTimeMillis(), 2L);

        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch allowRefresh = new CountDownLatch(1);
        CountDownLatch refreshFinished = new CountDownLatch(1);

        store(originalValue, 30);
        when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(1);
            executor.submit(() -> {
                try {
                    refreshStarted.countDown();
                    if (!allowRefresh.await(5, TimeUnit.SECONDS)) {
                        return;
                    }
                    runnable.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    refreshFinished.countDown();
                }
            });
            return null;
        }).when(earlyExpirationExecutor).submit(eq(REDIS_KEY), any(Runnable.class));

        handler.doHandle(context);

        // User puts a newer value into real Redis while async refresh is pending.
        assertThat(refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();
        valueOperations.set(REDIS_KEY, newValue, Duration.ofSeconds(30));

        allowRefresh.countDown();
        assertThat(refreshFinished.await(5, TimeUnit.SECONDS)).isTrue();
        CachedValue actual = (CachedValue) valueOperations.get(REDIS_KEY);

        assertThat(actual.getValue()).isEqualTo("new");
        // A changed value must not be shortened by the refresh task.
        assertThat(redisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS)).isGreaterThan(5L);
    }

    @Test
    @DisplayName("multiple async refreshes preserve the latest real Redis value")
    void multipleAsyncRefreshes_onlyLatestWins() throws InterruptedException {
        RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.ASYNC);
        CacheContext context1 = createContext(CacheOperation.GET, operation);
        CacheContext context2 = createContext(CacheOperation.GET, operation);
        CacheContext context3 = createContext(CacheOperation.GET, operation);

        CachedValue cachedValue1 = createCachedValue("value-1", 60, System.currentTimeMillis(), 1L);
        CachedValue cachedValue2 = createCachedValue("value-2", 60, System.currentTimeMillis(), 2L);
        CachedValue cachedValue3 = createCachedValue("value-3", 60, System.currentTimeMillis(), 3L);

        CountDownLatch allRefreshesSubmitted = new CountDownLatch(3);
        when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);
        doAnswer(invocation -> {
            allRefreshesSubmitted.countDown();
            return null;
        }).when(earlyExpirationExecutor).submit(anyString(), any(Runnable.class));

        store(cachedValue1, 30);
        handler.doHandle(context1);
        store(cachedValue2, 30);
        handler.doHandle(context2);
        store(cachedValue3, 30);
        handler.doHandle(context3);

        assertThat(allRefreshesSubmitted.await(5, TimeUnit.SECONDS)).isTrue();
        CachedValue actual = (CachedValue) valueOperations.get(REDIS_KEY);
        assertThat(actual.getValue()).isEqualTo("value-3");
        verify(earlyExpirationExecutor, times(3)).submit(eq(REDIS_KEY), any(Runnable.class));
    }

    @Test
    @DisplayName("real Lua CAS leaves the real TTL safe after metadata round-trip")
    void atomicLuaScript_preventsRaceBetweenVersionCheckAndTtlShorten() {
        RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.ASYNC);
        CacheContext context = createContext(CacheOperation.GET, operation);
        // The real serializer emits envelope version 2, which is the wire field
        // compared by the current Lua script.
        CachedValue cachedValue = createCachedValue("stable", 60, System.currentTimeMillis(), 2L);
        store(cachedValue, 30);
        when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(1);
            runnable.run();
            return null;
        }).when(earlyExpirationExecutor).submit(eq(REDIS_KEY), any(Runnable.class));

        handler.doHandle(context);

        assertThat(redisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS)).isGreaterThan(5L);
    }

    @Test
    @DisplayName("real Lua CAS skips TTL shortening after a concurrent value change")
    void atomicLuaScript_valueChanged_skipsTtlShorten() {
        RedisCacheableOperation operation = createEarlyExpirationOperation(true, 0.8, EarlyExpirationMode.ASYNC);
        CacheContext context = createContext(CacheOperation.GET, operation);
        CachedValue capturedValue = createCachedValue("captured", 60, System.currentTimeMillis(), 1L);
        CachedValue changedValue = createCachedValue("changed", 60, System.currentTimeMillis(), 2L);

        store(capturedValue, 30);
        when(earlyExpirationPolicy.shouldRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);
        doAnswer(invocation -> {
            // Replace the value in real Redis before the captured refresh runs.
            store(changedValue, 30);
            Runnable runnable = invocation.getArgument(1);
            runnable.run();
            return null;
        }).when(earlyExpirationExecutor).submit(eq(REDIS_KEY), any(Runnable.class));

        handler.doHandle(context);

        CachedValue actual = (CachedValue) valueOperations.get(REDIS_KEY);
        assertThat(actual.getValue()).isEqualTo("changed");
        assertThat(redisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS)).isGreaterThan(5L);
    }
}
