package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.EarlyExpirationDecision;
import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Early-refresh boundary tests owned by {@link EarlyRefresh}, with a fixed wall clock.
 */
@DisplayName("EarlyRefresh decision boundaries")
class EarlyRefreshTest {

    private static final String CACHE_NAME = "early-cache";
    private static final String REDIS_KEY = "early:key";

    @Test
    @DisplayName("non-positive cached TTL does not enter refresh window")
    void nonPositiveTtl_doesNotRefresh() {
        assertNeedsRefresh(0, 8, 0.2, false);
    }

    @Test
    @DisplayName("threshold at or outside valid range does not enter refresh window")
    void thresholdOutsideRange_doesNotRefresh() {
        assertNeedsRefresh(10, 8, 0.0, false);
        assertNeedsRefresh(10, 8, -0.1, false);
        assertNeedsRefresh(10, 8, 1.0, false);
        assertNeedsRefresh(10, 8, 1.1, false);
    }

    @Test
    @DisplayName("exact threshold enters refresh window")
    void exactThreshold_refreshes() {
        assertNeedsRefresh(10, 8, 0.2, true);
    }

    @Test
    @DisplayName("just before threshold stays out and just after enters")
    void thresholdBoundary_isStrictlyCovered() {
        assertNeedsRefresh(10, 7, 0.2, false);
        assertNeedsRefresh(10, 9, 0.2, true);
    }

    @Test
    @DisplayName("scheduled real refresh retries a transient read failure and completes once")
    void asyncRefresh_transientReadFailure_retriesRealTask() {
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        CachedValue cached = CachedValue.forTest("cached", 60, 0L, 1L, false);
        when(values.get(REDIS_KEY)).thenReturn(cached)
                .thenThrow(new IllegalStateException("transient read failure"))
                .thenReturn(cached);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        when(redis.execute(any(RedisCallback.class))).thenReturn(true);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolEarlyExpirationExecutor executor = inlineExecutor(registry);
        try {
            EarlyRefresh refresh = new EarlyRefresh(
                    Clock.fixed(Instant.ofEpochSecond(50), ZoneOffset.UTC), executor, redis, values);
            RedisCacheableOperation operation = asyncOperation();

            assertThat(refresh.evaluate(context(operation)).decision())
                    .isEqualTo(EarlyExpirationDecision.asyncRefresh());
            verify(values, times(3)).get(REDIS_KEY);
            verify(redis).execute(any(RedisCallback.class));
            assertThat(executor.getActiveCount()).isZero();
            assertThat(registry.get("prerefresh.submitted").counter().count()).isEqualTo(1);
            assertThat(registry.get("prerefresh.completed").counter().count()).isEqualTo(1);
        } finally {
            executor.shutdown();
            registry.close();
        }
    }

    @Test
    @DisplayName("scheduled real refresh isolates exhausted CAS failures and completes once")
    void asyncRefresh_permanentCasFailure_isolatedAfterRetries() {
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        CachedValue cached = CachedValue.forTest("cached", 60, 0L, 1L, false);
        when(values.get(REDIS_KEY)).thenReturn(cached);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        when(redis.execute(any(RedisCallback.class))).thenThrow(new IllegalStateException("CAS failure"));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolEarlyExpirationExecutor executor = inlineExecutor(registry);
        try {
            EarlyRefresh refresh = new EarlyRefresh(
                    Clock.fixed(Instant.ofEpochSecond(50), ZoneOffset.UTC), executor, redis, values);
            RedisCacheableOperation operation = asyncOperation();

            assertThat(refresh.evaluate(context(operation)).decision())
                    .isEqualTo(EarlyExpirationDecision.asyncRefresh());
            verify(redis, times(RefreshRetryPolicy.MAX_RETRY_COUNT)).execute(any(RedisCallback.class));
            assertThat(executor.getActiveCount()).isZero();
            assertThat(registry.get("prerefresh.completed").counter().count()).isEqualTo(1);
        } finally {
            executor.shutdown();
            registry.close();
        }
    }

    private ThreadPoolEarlyExpirationExecutor inlineExecutor(SimpleMeterRegistry registry) {
        ExecutorService worker = mock(ExecutorService.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(worker).execute(any(Runnable.class));
        return new ThreadPoolEarlyExpirationExecutor(worker, new ConcurrentHashMap<>(), registry, 30_000L);
    }

    private RedisCacheableOperation asyncOperation() {
        return RedisCacheableOperation.builder()
                .name(CACHE_NAME).cacheNames(CACHE_NAME)
                .enableEarlyExpiration(true).earlyExpirationThreshold(0.2)
                .earlyExpirationMode(EarlyExpirationMode.ASYNC).build();
    }

    private void assertNeedsRefresh(long ttl, long elapsedSeconds, double threshold, boolean expected) {
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        CachedValue cachedValue = CachedValue.forTest(
                "cached", ttl, 0L, 1L, false);
        when(values.get(REDIS_KEY)).thenReturn(cachedValue);
        EarlyRefresh earlyRefresh = new EarlyRefresh(
                Clock.fixed(Instant.ofEpochSecond(elapsedSeconds), ZoneOffset.UTC),
                mock(ThreadPoolEarlyExpirationExecutor.class),
                mock(RedisTemplate.class),
                values);

        EarlyRefresh.Evaluation evaluation = earlyRefresh.evaluate(context(operation(threshold)));

        assertThat(evaluation).isNotNull();
        EarlyExpirationDecision decision = evaluation.decision();
        assertThat(decision.needsRefresh()).isEqualTo(expected);
        if (!expected) {
            assertThat(decision).isEqualTo(EarlyExpirationDecision.noRefresh());
        }
    }

    private CacheContext context(RedisCacheableOperation operation) {
        return new CacheContext(new CacheInput(
                CacheOperation.GET,
                CACHE_NAME,
                REDIS_KEY,
                "key",
                null,
                null,
                null,
                operation));
    }

    private RedisCacheableOperation operation(double threshold) {
        return RedisCacheableOperation.builder()
                .name(CACHE_NAME)
                .cacheNames(CACHE_NAME)
                .enableEarlyExpiration(true)
                .earlyExpirationThreshold(threshold)
                .earlyExpirationMode(EarlyExpirationMode.SYNC)
                .build();
    }
}
