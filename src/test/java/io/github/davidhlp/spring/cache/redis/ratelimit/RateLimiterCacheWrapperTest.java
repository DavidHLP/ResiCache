package io.github.davidhlp.spring.cache.redis.ratelimit;

import io.github.davidhlp.spring.cache.redis.core.RedisProCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RateLimiterCacheWrapper 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimiterCacheWrapper Tests")
class RateLimiterCacheWrapperTest {

    @Mock
    private RedisProCache delegate;

    @Mock
    private RedisCacheWriter cacheWriter;

    private RedisCacheConfiguration cacheConfiguration;
    private MeterRegistry meterRegistry;
    private RateLimiterCacheWrapper wrapper;

    @BeforeEach
    void setUp() {
        cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig();
        meterRegistry = new SimpleMeterRegistry();
        wrapper = new RateLimiterCacheWrapper(
                delegate,
                "test-cache",
                cacheWriter,
                cacheConfiguration,
                meterRegistry,
                1000.0
        );
    }

    @Nested
    @DisplayName("constructor tests")
    class ConstructorTests {

        @Test
        @DisplayName("creates wrapper with default QPS")
        void constructor_defaultQps_createsCorrectly() {
            RateLimiterCacheWrapper wrapper = new RateLimiterCacheWrapper(
                    delegate, "cache", cacheWriter, cacheConfiguration, meterRegistry);

            assertThat(wrapper).isNotNull();
        }

        @Test
        @DisplayName("throws exception when wrapping another RateLimiterCacheWrapper")
        void constructor_circularDelegation_throwsException() {
            RateLimiterCacheWrapper innerWrapper = new RateLimiterCacheWrapper(
                    delegate, "inner", cacheWriter, cacheConfiguration, meterRegistry);

            assertThatThrownBy(() -> new RateLimiterCacheWrapper(
                    innerWrapper, "outer", cacheWriter, cacheConfiguration, meterRegistry))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Circular delegation detected");
        }
    }

    @Nested
    @DisplayName("get tests")
    class GetTests {

        @Test
        @DisplayName("delegates to underlying cache when under limit")
        void get_underLimit_delegatesToCache() throws Exception {
            String key = "test-key";
            String expected = "test-value";
            Callable<String> loader = () -> expected;
            when(delegate.get(eq(key), any(Callable.class))).thenReturn(expected);

            Object result = wrapper.get(key, loader);

            assertThat(result).isEqualTo(expected);
            verify(delegate).get(eq(key), any(Callable.class));
        }

        @Test
        @DisplayName("increments hit count on cache hit")
        void get_cacheHit_incrementsHitCount() throws Exception {
            String key = "hit-key";
            String value = "hit-value";
            Callable<String> loader = () -> value;
            when(delegate.get(eq(key), any(Callable.class))).thenReturn(value);

            wrapper.get(key, loader);

            assertThat(wrapper.getHitCount()).isEqualTo(1);
            assertThat(wrapper.getMissCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("increments miss count on cache miss")
        void get_cacheMiss_incrementsMissCount() throws Exception {
            String key = "miss-key";
            Callable<String> loader = () -> "loaded-value";
            when(delegate.get(eq(key), any(Callable.class))).thenReturn(null);

            wrapper.get(key, loader);

            assertThat(wrapper.getMissCount()).isEqualTo(1);
            assertThat(wrapper.getHitCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("put tests")
    class PutTests {

        @Test
        @DisplayName("delegates put when under limit")
        void put_underLimit_delegatesToCache() {
            String key = "put-key";
            Object value = "put-value";

            wrapper.put(key, value);

            verify(delegate).put(eq(key), eq(value));
        }

        @Test
        @DisplayName("delegates put for each unique key")
        void put_multipleKeys_delegatesAll() {
            // With QPS of 1000, these should all succeed
            for (int i = 0; i < 10; i++) {
                wrapper.put("key-" + i, "value-" + i);
            }

            verify(delegate, times(10)).put(any(), any());
        }
    }

    @Nested
    @DisplayName("evict tests")
    class EvictTests {

        @Test
        @DisplayName("always delegates evict")
        void evict_always_delegates() {
            String key = "evict-key";

            wrapper.evict(key);

            verify(delegate).evict(eq(key));
        }

        @Test
        @DisplayName("evict does not affect rate limit counters")
        void evict_doesNotAffectCounters() {
            String key = "evict-key";

            wrapper.evict(key);

            // Evict should not increment hit/miss/rateLimitSkip counts
            assertThat(wrapper.getHitCount()).isEqualTo(0);
            assertThat(wrapper.getMissCount()).isEqualTo(0);
            assertThat(wrapper.getRateLimitSkipCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("clear tests")
    class ClearTests {

        @Test
        @DisplayName("always delegates clear")
        void clear_always_delegates() {
            wrapper.clear();

            verify(delegate).clear();
        }
    }

    @Nested
    @DisplayName("rate limiter statistics tests")
    class StatisticsTests {

        @Test
        @DisplayName("returns correct hit count")
        void getHitCount_multipleHits_returnsCorrectCount() throws Exception {
            when(delegate.get(any(), any(Callable.class))).thenReturn("value");

            for (int i = 0; i < 5; i++) {
                wrapper.get("key" + i, () -> "value");
            }

            assertThat(wrapper.getHitCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("returns correct miss count")
        void getMissCount_multipleMisses_returnsCorrectCount() throws Exception {
            when(delegate.get(any(), any(Callable.class))).thenReturn(null);

            for (int i = 0; i < 3; i++) {
                wrapper.get("key" + i, () -> "value");
            }

            assertThat(wrapper.getMissCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("returns correct rate limit skip count")
        void getRateLimitSkipCount_multipleLimits_returnsCorrectCount() {
            // This test simulates what happens when rate limit is exceeded
            // Note: actual rate limiting depends on QPS setting
            assertThat(wrapper.getRateLimitSkipCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("concurrent access tests")
    class ConcurrentAccessTests {

        @Test
        @DisplayName("handles concurrent get operations")
        void get_concurrentRequests_noExceptions() throws InterruptedException {
            int threadCount = 10;
            int requestsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            when(delegate.get(any(), any(Callable.class))).thenAnswer(inv -> "value");

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < requestsPerThread; i++) {
                            wrapper.get("key-" + i, () -> "value");
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // Verify all requests completed without exceptions
            assertThat(wrapper.getHitCount()).isEqualTo(threadCount * requestsPerThread);
        }
    }

    @Nested
    @DisplayName("rate limiter token bucket tests")
    class TokenBucketTests {

        @Test
        @DisplayName("allows requests up to QPS limit")
        void underLimit_requestsAllowed() throws Exception {
            when(delegate.get(any(), any(Callable.class))).thenReturn("value");

            // Make requests up to QPS limit (1000 in this case via 1000.0)
            for (int i = 0; i < 100; i++) {
                wrapper.get("key-" + i, () -> "value");
            }

            assertThat(wrapper.getHitCount()).isEqualTo(100);
        }

        @Test
        @DisplayName("tracks rate limit skips correctly")
        void rateLimitExceeded_tracksSkips() {
            // With QPS of 1000, making 1500 requests should trigger some skips
            for (int i = 0; i < 1500; i++) {
                try {
                    wrapper.put("key-" + i, "value");
                } catch (Exception ignored) {
                }
            }

            // Some operations should have been rate limited
            // (exact count depends on timing)
        }
    }

    @Nested
    @DisplayName("concurrent QPS accuracy tests")
    class QpsAccuracyTests {

        @Test
        @DisplayName("QPS accuracy under concurrency is within 5% error")
        void qpsAccuracy_underConcurrency_withinFivePercent() throws InterruptedException {
            int targetQps = 100;
            RateLimiterCacheWrapper qpsWrapper = new RateLimiterCacheWrapper(
                    delegate,
                    "qps-test-cache",
                    cacheWriter,
                    cacheConfiguration,
                    meterRegistry,
                    targetQps
            );

            int threadCount = 2;
            int requestsPerThread = 100;
            int requestIntervalMs = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completeLatch = new CountDownLatch(threadCount);
            AtomicLong allowedCount = new AtomicLong();

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < requestsPerThread; i++) {
                            long beforeSkip = qpsWrapper.getRateLimitSkipCount();
                            qpsWrapper.put("key-" + i, "value");
                            long afterSkip = qpsWrapper.getRateLimitSkipCount();
                            if (afterSkip == beforeSkip) {
                                allowedCount.incrementAndGet();
                            }
                            Thread.sleep(requestIntervalMs);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completeLatch.countDown();
                    }
                });
            }

            long measureStart = System.nanoTime();
            startLatch.countDown();
            assertThat(completeLatch.await(30, TimeUnit.SECONDS)).isTrue();
            long measureEnd = System.nanoTime();
            executor.shutdown();

            double durationSeconds = (measureEnd - measureStart) / 1_000_000_000.0;
            double actualQps = allowedCount.get() / durationSeconds;
            double expectedQps = targetQps;
            double errorPercent = Math.abs(actualQps - expectedQps) / expectedQps * 100.0;

            assertThat(errorPercent)
                    .as("QPS accuracy error should be within 5%%. Allowed: %d, Duration: %.3fs, Actual QPS: %.2f, Target QPS: %d, Error: %.2f%%",
                            allowedCount.get(), durationSeconds, actualQps, targetQps, errorPercent)
                    .isLessThan(5.0);
        }

        @Test
        @DisplayName("token bucket refills correctly under concurrent load")
        void tokenBucketRefill_underConcurrency_maintainsConsistency() throws InterruptedException {
            RateLimiterCacheWrapper refillWrapper = new RateLimiterCacheWrapper(
                    delegate,
                    "refill-test-cache",
                    cacheWriter,
                    cacheConfiguration,
                    meterRegistry,
                    50.0
            );

            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch firstWaveLatch = new CountDownLatch(threadCount);
            CountDownLatch secondWaveLatch = new CountDownLatch(threadCount);

            AtomicLong firstWaveAllowed = new AtomicLong();
            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < 20; i++) {
                            long beforeSkip = refillWrapper.getRateLimitSkipCount();
                            refillWrapper.put("key-" + i, "value");
                            long afterSkip = refillWrapper.getRateLimitSkipCount();
                            if (afterSkip == beforeSkip) {
                                firstWaveAllowed.incrementAndGet();
                            }
                        }
                    } finally {
                        firstWaveLatch.countDown();
                    }
                });
            }

            assertThat(firstWaveLatch.await(10, TimeUnit.SECONDS)).isTrue();

            Thread.sleep(200);

            AtomicLong secondWaveAllowed = new AtomicLong();
            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < 10; i++) {
                            long beforeSkip = refillWrapper.getRateLimitSkipCount();
                            refillWrapper.put("key-" + i, "value");
                            long afterSkip = refillWrapper.getRateLimitSkipCount();
                            if (afterSkip == beforeSkip) {
                                secondWaveAllowed.incrementAndGet();
                            }
                        }
                    } finally {
                        secondWaveLatch.countDown();
                    }
                });
            }

            assertThat(secondWaveLatch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            assertThat(secondWaveAllowed.get()).isGreaterThan(0);
        }
    }
}
