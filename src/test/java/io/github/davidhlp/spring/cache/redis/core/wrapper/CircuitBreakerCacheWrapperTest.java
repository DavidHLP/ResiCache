package io.github.davidhlp.spring.cache.redis.core.wrapper;

import io.github.davidhlp.spring.cache.redis.core.RedisProCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CircuitBreakerCacheWrapper 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CircuitBreakerCacheWrapper Tests")
class CircuitBreakerCacheWrapperTest {

    @Mock
    private RedisProCache delegate;

    private CircuitBreakerCacheWrapper wrapper;

    @BeforeEach
    void setUp() {
        when(delegate.getName()).thenReturn("test-cache");
        wrapper = new CircuitBreakerCacheWrapper(delegate);
    }

    private <T> Callable<T> mockLoader(T value) {
        return () -> value;
    }

    @Nested
    @DisplayName("正常状态 (CLOSED)")
    class ClosedStateTests {

        @Test
        @DisplayName("缓存命中时直接返回缓存值")
        void get_cacheHit_returnsCachedValue() throws Exception {
            String cachedValue = "cached";
            when(delegate.get(any(), any(Callable.class))).thenReturn(cachedValue);

            String result = wrapper.get("key", mockLoader("loaded"));

            assertThat(result).isEqualTo(cachedValue);
            verify(delegate).get(any(), any(Callable.class));
        }

        @Test
        @DisplayName("缓存未命中时返回null不调用loader")
        void get_cacheMiss_returnsNull() throws Exception {
            when(delegate.get(any(), any(Callable.class))).thenReturn(null);

            // Note: wrapper returns null when cache misses, it doesn't call loader
            String result = wrapper.get("key", mockLoader("loaded"));

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("缓存失败时fallback到loader")
        void get_cacheFails_callsLoader() throws Exception {
            when(delegate.get(any(), any(Callable.class))).thenThrow(new RuntimeException("Cache error"));
            String loadedValue = "loaded";

            String result = wrapper.get("key", mockLoader(loadedValue));

            assertThat(result).isEqualTo(loadedValue);
        }
    }

    @Nested
    @DisplayName("getName")
    class GetNameTests {

        @Test
        @DisplayName("返回底层缓存的名称")
        void getName_returnsDelegateName() {
            String result = wrapper.getName();

            assertThat(result).isEqualTo("test-cache");
            verify(delegate).getName();
        }
    }

    @Nested
    @DisplayName("状态转换 - 失败阈值")
    class FailureThresholdTests {

        @Test
        @DisplayName("单次失败不触发断路器打开")
        void get_singleFailure_circuitStaysClosed() throws Exception {
            when(delegate.get(any(), any(Callable.class)))
                    .thenThrow(new RuntimeException("Cache error"))
                    .thenReturn("success");

            // First call fails
            try {
                wrapper.get("key1", () -> "fail");
            } catch (Exception ignored) {}

            // Second call should succeed (not open yet)
            String result = wrapper.get("key2", mockLoader("loaded"));
            assertThat(result).isEqualTo("success");
        }

        @Test
        @DisplayName("loader失败但缓存成功时不记录失败")
        void get_loaderFails_cacheSucceeds_noFailureRecorded() throws Exception {
            when(delegate.get(any(), any(Callable.class))).thenReturn("cached");

            // Even if loader fails later, as long as cache succeeds no failure is recorded
            String result = wrapper.get("key", mockLoader("loaded"));
            assertThat(result).isEqualTo("cached");
        }
    }

    @Nested
    @DisplayName("边界情况")
    class EdgeCaseTests {

        @Test
        @DisplayName("loader抛出检查异常时包装为RuntimeException")
        void get_loaderThrowsCheckedException_wrappedInRuntimeException() {
            when(delegate.get(any(), any(Callable.class))).thenThrow(new RuntimeException("Cache error"));

            assertThatThrownBy(() -> wrapper.get("key", () -> {
                throw new Exception("Checked exception");
            }))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Both cache and loader failed");
        }

        @Test
        @DisplayName("两次失败后第三次成功会清理失败计数")
        void get_failureThenSuccess_failureCountCleared() throws Exception {
            // Setup: first call fails, second succeeds
            when(delegate.get(any(), any(Callable.class)))
                    .thenThrow(new RuntimeException("Cache error"))
                    .thenThrow(new RuntimeException("Cache error"))
                    .thenReturn("success");

            try {
                wrapper.get("key1", () -> "fail1");
            } catch (Exception ignored) {}

            try {
                wrapper.get("key2", () -> "fail2");
            } catch (Exception ignored) {}

            // Third call should succeed and clear failure count
            String result = wrapper.get("key3", mockLoader("loaded"));
            assertThat(result).isEqualTo("success");
        }
    }

    @Nested
    @DisplayName("并发测试")
    class ConcurrentTests {

        @BeforeEach
        void resetCircuitBreaker() throws Exception {
            java.lang.reflect.Field field = CircuitBreakerCacheWrapper.class.getDeclaredField("circuitBreakers");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, ?> map = (java.util.Map<String, ?>) field.get(wrapper);
            map.clear();
        }

        @Test
        @DisplayName("并发失败记录保证断路器正确打开")
        void concurrentFailures_circuitOpensConsistently() throws Exception {
            when(delegate.get(any(), any(Callable.class)))
                    .thenThrow(new RuntimeException("Cache error"));

            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        wrapper.get("key", () -> "fallback");
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // 断路器应该已经打开，后续请求不应再调用 delegate
            // 重置 mock 来验证 OPEN 状态下的行为
            reset(delegate);
            when(delegate.getName()).thenReturn("test-cache");

            String result = wrapper.get("key", () -> "fallback");
            assertThat(result).isEqualTo("fallback");
            verify(delegate, never()).get(any(), any(Callable.class));
        }

        @Test
        @DisplayName("并发成功和失败混合不会导致状态错乱或死锁")
        void concurrentSuccessAndFailure_noDeadlockOrInconsistentState() throws Exception {
            AtomicInteger callCount = new AtomicInteger(0);
            when(delegate.get(any(), any(Callable.class))).thenAnswer(inv -> {
                int count = callCount.incrementAndGet();
                if (count % 2 == 0) {
                    return "success";
                } else {
                    throw new RuntimeException("Cache error");
                }
            });

            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        wrapper.get("key", () -> "fallback");
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();

            resetCircuitBreaker();
            reset(delegate);
            when(delegate.getName()).thenReturn("test-cache");
            when(delegate.get(any(), any(Callable.class))).thenReturn("final");

            String result = wrapper.get("key", () -> "fallback");
            assertThat(result).isEqualTo("final");
        }

        @Test
        @DisplayName("并发多次记录失败保证状态计数一致")
        void concurrentRecordFailure_failureCountConsistent() throws Exception {
            when(delegate.get(any(), any(Callable.class)))
                    .thenThrow(new RuntimeException("Cache error"));

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        wrapper.get("key", () -> "fallback");
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            reset(delegate);
            when(delegate.getName()).thenReturn("test-cache");

            String result = wrapper.get("key", () -> "fallback");
            assertThat(result).isEqualTo("fallback");
            verify(delegate, never()).get(any(), any(Callable.class));
        }
    }
}
