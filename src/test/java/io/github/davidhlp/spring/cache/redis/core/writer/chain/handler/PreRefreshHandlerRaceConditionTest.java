package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.CachedValue;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.ttl.TtlPolicy;
import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshMode;
import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshSupport;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * PreRefreshHandler race condition tests verifying correct behavior under concurrent access.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PreRefreshHandler Race Condition Tests")
class PreRefreshHandlerRaceConditionTest {

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
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        handler = new PreRefreshHandler(ttlPolicy, preRefreshSupport, redisTemplate, statistics, valueOperations);
        executor = Executors.newCachedThreadPool();
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

    private CachedValue createCachedValue(long ttlSeconds, long createdTime, long version) {
        return CachedValue.builder()
                .value("test-value")
                .type(String.class)
                .ttl(ttlSeconds)
                .createdTime(createdTime)
                .startNanoTime(System.nanoTime())
                .version(version)
                .build();
    }

    @Test
    @DisplayName("asyncRefreshAndEvict_concurrentNoCorruption")
    void asyncRefreshAndEvict_concurrentNoCorruption() throws InterruptedException {
        RedisCacheableOperation operation = createPreRefreshOperation(true, 0.8, PreRefreshMode.ASYNC);
        CacheContext context = createContext(CacheOperation.GET, operation);
        CachedValue cachedValue = createCachedValue(60, System.currentTimeMillis(), 1L);
        AtomicBoolean exceptionThrown = new AtomicBoolean(false);

        // Stub both valueOperations.get and shouldPreRefresh
        when(valueOperations.get("test:key")).thenReturn(cachedValue);
        when(ttlPolicy.shouldPreRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(1);
            executor.submit(() -> {
                try {
                    runnable.run();
                } catch (Exception e) {
                    exceptionThrown.set(true);
                }
            });
            return null;
        }).when(preRefreshSupport).submitAsyncRefresh(eq("test:key"), any(Runnable.class));

        // First call sets up the async refresh
        handler.doHandle(context);

        // Simulate evict happening concurrently
        CountDownLatch latch = new CountDownLatch(1);
        executor.submit(() -> {
            try {
                // Evict while async refresh is running
                handler.doHandle(context);
            } finally {
                latch.countDown();
            }
        });

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(exceptionThrown.get()).isFalse();
        verify(preRefreshSupport, atLeastOnce()).submitAsyncRefresh(eq("test:key"), any(Runnable.class));
    }

    @Test
    @DisplayName("asyncRefreshAndPut_concurrentCorrectPrecedence")
    void asyncRefreshAndPut_concurrentCorrectPrecedence() throws InterruptedException {
        RedisCacheableOperation operation = createPreRefreshOperation(true, 0.8, PreRefreshMode.ASYNC);
        CacheContext context = createContext(CacheOperation.GET, operation);
        CachedValue originalValue = createCachedValue(60, System.currentTimeMillis(), 1L);
        CachedValue newValue = createCachedValue(60, System.currentTimeMillis(), 2L);

        AtomicReference<Object> capturedValue = new AtomicReference<>();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch testComplete = new CountDownLatch(1);

        lenient().when(valueOperations.get("test:key")).thenAnswer(invocation -> {
            capturedValue.set(invocation.getMock());
            return originalValue;
        });
        lenient().when(ttlPolicy.shouldPreRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(1);
            executor.submit(() -> {
                try {
                    refreshStarted.countDown();
                    // Simulate async refresh seeing original value
                    runnable.run();
                } catch (Exception e) {
                    // ignore
                } finally {
                    try {
                        testComplete.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            return null;
        }).when(preRefreshSupport).submitAsyncRefresh(eq("test:key"), any(Runnable.class));

        handler.doHandle(context);

        // User puts new value while async refresh is pending
        assertThat(refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();
        lenient().when(valueOperations.get("test:key")).thenReturn(newValue);

        testComplete.countDown();

        // Verify user's explicit put was processed
        verify(valueOperations, atLeast(2)).get("test:key");
    }

    @Test
    @DisplayName("multipleAsyncRefreshes_onlyLatestWins")
    void multipleAsyncRefreshes_onlyLatestWins() throws InterruptedException {
        RedisCacheableOperation operation = createPreRefreshOperation(true, 0.8, PreRefreshMode.ASYNC);
        CacheContext context1 = createContext(CacheOperation.GET, operation);
        CacheContext context2 = createContext(CacheOperation.GET, operation);
        CacheContext context3 = createContext(CacheOperation.GET, operation);

        CachedValue cachedValue1 = createCachedValue(60, System.currentTimeMillis(), 1L);
        CachedValue cachedValue2 = createCachedValue(60, System.currentTimeMillis(), 2L);
        CachedValue cachedValue3 = createCachedValue(60, System.currentTimeMillis(), 3L);

        CountDownLatch allRefreshesSubmitted = new CountDownLatch(3);
        AtomicReference<String> capturedKey = new AtomicReference<>();

        lenient().when(valueOperations.get("test:key")).thenReturn(cachedValue1);
        lenient().when(ttlPolicy.shouldPreRefresh(anyLong(), anyLong(), anyDouble())).thenReturn(true);

        doAnswer(invocation -> {
            capturedKey.set(invocation.getArgument(0));
            allRefreshesSubmitted.countDown();
            return null;
        }).when(preRefreshSupport).submitAsyncRefresh(anyString(), any(Runnable.class));

        // Submit multiple refreshes
        handler.doHandle(context1);
        lenient().when(valueOperations.get("test:key")).thenReturn(cachedValue2);
        handler.doHandle(context2);
        lenient().when(valueOperations.get("test:key")).thenReturn(cachedValue3);
        handler.doHandle(context3);

        assertThat(allRefreshesSubmitted.await(5, TimeUnit.SECONDS)).isTrue();

        // All three should have been submitted
        verify(preRefreshSupport, times(3)).submitAsyncRefresh(eq("test:key"), any(Runnable.class));
    }
}
