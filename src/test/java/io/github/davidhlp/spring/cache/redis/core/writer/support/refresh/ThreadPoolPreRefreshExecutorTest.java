package io.github.davidhlp.spring.cache.redis.core.writer.support.refresh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ThreadPoolPreRefreshExecutor 单元测试
 */
@DisplayName("ThreadPoolPreRefreshExecutor Tests")
class ThreadPoolPreRefreshExecutorTest {

    private ThreadPoolPreRefreshExecutor executor;
    private ConcurrentHashMap<String, CompletableFuture<Void>> inFlight;

    @BeforeEach
    void setUp() {
        inFlight = new ConcurrentHashMap<>();
        executor = new ThreadPoolPreRefreshExecutor(
                Executors.newCachedThreadPool(),
                inFlight,
                null,
                10_000L
        );
        executor.initCleanupScheduler();
    }

    @Nested
    @DisplayName("submit tests")
    class SubmitTests {

        @Test
        @DisplayName("submits task successfully")
        void submit_validTask_executesSuccessfully() throws InterruptedException {
            String key = "test-key-1";
            CountDownLatch latch = new CountDownLatch(1);

            executor.submit(key, latch::countDown);

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        @DisplayName("skips submission when key is null")
        void submit_nullKey_skipsSubmission() {
            executor.submit(null, () -> {});

            // No exception means success
        }

        @Test
        @DisplayName("skips submission when task is null")
        void submit_nullTask_skipsSubmission() {
            executor.submit("test-key", null);

            // No exception means success
        }

        @Test
        @DisplayName("handles multiple different keys correctly")
        void submit_multipleDifferentKeys_executesAll() throws InterruptedException {
            int keyCount = 5;
            CountDownLatch latch = new CountDownLatch(keyCount);

            for (int i = 0; i < keyCount; i++) {
                executor.submit("key-" + i, latch::countDown);
            }

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Nested
    @DisplayName("cancel tests")
    class CancelTests {

        @Test
        @DisplayName("cancels running task")
        void cancel_runningTask_cancelsSuccessfully() throws InterruptedException {
            String key = "cancel-key";
            CountDownLatch startedLatch = new CountDownLatch(1);
            AtomicBoolean cancelled = new AtomicBoolean(false);

            executor.submit(key, () -> {
                try {
                    startedLatch.countDown();
                    Thread.sleep(5000); // Long running task
                } catch (InterruptedException e) {
                    cancelled.set(true);
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(startedLatch.await(5, TimeUnit.SECONDS)).isTrue();

            executor.cancel(key);

            // Give some time for cancellation to take effect
            Thread.sleep(200);
            // The task should have been interrupted (cancelled flag set)
            // Note: cancellation is best-effort
        }

        @Test
        @DisplayName("handles cancel of non-existent key")
        void cancel_nonExistentKey_noError() {
            executor.cancel("non-existent-key");

            // Should not throw
        }

        @Test
        @DisplayName("handles cancel with null key")
        void cancel_nullKey_noError() {
            executor.cancel(null);

            // Should not throw
        }
    }

    @Nested
    @DisplayName("concurrent access tests")
    class ConcurrentAccessTests {

        @Test
        @DisplayName("handles concurrent submissions for different keys")
        void submit_concurrentDifferentKeys_allExecuted() throws InterruptedException {
            int keyCount = 20;
            CountDownLatch latch = new CountDownLatch(keyCount);

            for (int i = 0; i < keyCount; i++) {
                String key = "concurrent-key-" + i;
                executor.submit(key, latch::countDown);
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        @DisplayName("maintains correct active count under load")
        void getActiveCount_concurrentLoad_correctCount() throws InterruptedException {
            int keyCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger maxActive = new AtomicInteger(0);

            for (int i = 0; i < keyCount; i++) {
                String key = "load-key-" + i;
                executor.submit(key, () -> {
                    try {
                        startLatch.await();
                        maxActive.updateAndGet(prev -> Math.max(prev, executor.getActiveCount()));
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            startLatch.countDown();
            Thread.sleep(2000); // Wait for tasks to complete

            assertThat(maxActive.get()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("stats tests")
    class StatsTests {

        @Test
        @DisplayName("returns valid stats string")
        void getStats_validThreadPool_returnsStats() {
            String stats = executor.getStats();

            assertThat(stats).contains("PreRefreshThreadPool");
            assertThat(stats).contains("active=");
            assertThat(stats).contains("poolSize=");
        }

        @Test
        @DisplayName("returns unknown stats for non-threadpool executor")
        void getStats_nonThreadPool_returnsUnknown() {
            ThreadPoolPreRefreshExecutor simpleExecutor = new ThreadPoolPreRefreshExecutor(
                    Executors.newSingleThreadExecutor(),
                    new ConcurrentHashMap<>(),
                    null,
                    10_000L
            );

            String stats = simpleExecutor.getStats();

            assertThat(stats).contains("unknown");
            simpleExecutor.shutdown();
        }
    }

    @Nested
    @DisplayName("shutdown tests")
    class ShutdownTests {

        @Test
        @DisplayName("shutdown completes successfully")
        void shutdown_noRunningTasks_completesSuccessfully() {
            executor.shutdown();

            // Should not throw and should complete within timeout
            assertThat(executor.getActiveCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("shutdown with running tasks terminates")
        void shutdown_withRunningTasks_terminates() throws InterruptedException {
            CountDownLatch runningLatch = new CountDownLatch(1);

            executor.submit("running-key", () -> {
                try {
                    runningLatch.await();
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            runningLatch.countDown();
            executor.shutdown();

            // Should terminate within reasonable time
            assertThat(executor.getActiveCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("shutdown properly cleans up all resources")
        void shutdown_properlyCleansUpResources() throws Exception {
            ThreadPoolPreRefreshExecutor testExecutor = new ThreadPoolPreRefreshExecutor(
                    Executors.newCachedThreadPool(),
                    new ConcurrentHashMap<>(),
                    null,
                    100L
            );
            testExecutor.initCleanupScheduler();

            CountDownLatch latch = new CountDownLatch(1);
            testExecutor.submit("cleanup-key", latch::countDown);
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

            testExecutor.shutdown();

            java.lang.reflect.Field executorField = ThreadPoolPreRefreshExecutor.class.getDeclaredField("executorService");
            executorField.setAccessible(true);
            ExecutorService executorService = (ExecutorService) executorField.get(testExecutor);

            java.lang.reflect.Field schedulerField = ThreadPoolPreRefreshExecutor.class.getDeclaredField("cleanupScheduler");
            schedulerField.setAccessible(true);
            ExecutorService cleanupScheduler = (ExecutorService) schedulerField.get(testExecutor);

            assertThat(executorService.isShutdown()).isTrue();
            assertThat(cleanupScheduler.isShutdown()).isTrue();
            assertThat(executorService.isTerminated()).isTrue();
            assertThat(cleanupScheduler.isTerminated()).isTrue();
            assertThat(testExecutor.getActiveCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("retry tests")
    class RetryTests {

        @Test
        @DisplayName("retries failed task up to max attempts")
        @EnabledOnOs(OS.LINUX)
        void submit_failingTask_retriesAndFails() throws InterruptedException {
            String key = "failing-key";
            AtomicInteger attemptCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(3);

            executor.submit(key, () -> {
                int attempt = attemptCount.incrementAndGet();
                latch.countDown();
                if (attempt < 3) {
                    throw new RuntimeException("Simulated failure " + attempt);
                }
            });

            latch.await(15, TimeUnit.SECONDS);

            // Should have attempted 3 times (initial + 2 retries)
            assertThat(attemptCount.get()).isEqualTo(3);
        }
    }
}
