package io.github.davidhlp.spring.cache.redis.cache;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ThreadPoolEarlyExpirationExecutor 单元测试
 */
@DisplayName("ThreadPoolEarlyExpirationExecutor Tests")
class ThreadPoolEarlyExpirationExecutorTest {

    private ThreadPoolEarlyExpirationExecutor executor;
    private ExecutorService workers;
    private ConcurrentHashMap<String, CompletableFuture<Void>> inFlight;

    @BeforeEach
    void setUp() {
        inFlight = new ConcurrentHashMap<>();
        workers = Executors.newCachedThreadPool();
        executor = new ThreadPoolEarlyExpirationExecutor(
                workers,
                inFlight,
                null
        );
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdown();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void saturatedPoolRunsInlineAndRemovesCompletedEntry() throws Exception {
        ThreadPoolEarlyExpirationExecutor bounded = new ThreadPoolEarlyExpirationExecutor(1, 1, 1, null);
        ExecutorService boundedPool = (ExecutorService) org.springframework.test.util.ReflectionTestUtils
                .getField(bounded, "executorService");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = () -> {
            started.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            bounded.submit("running", blocker);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            bounded.submit("queued", blocker);
            Thread caller = Thread.currentThread();
            java.util.concurrent.atomic.AtomicReference<Thread> executedBy = new java.util.concurrent.atomic.AtomicReference<>();
            bounded.submit("inline", () -> executedBy.set(Thread.currentThread()));
            assertThat(executedBy.get()).isSameAs(caller);
            assertThat(bounded.getActiveCount()).isEqualTo(2);
        } finally {
            release.countDown();
            bounded.shutdown();
            assertThat(boundedPool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(bounded.getActiveCount()).isZero();
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

            assertThat(inFlight).isEmpty();
        }

        @Test
        @DisplayName("skips submission when task is null")
        void submit_nullTask_skipsSubmission() {
            executor.submit("test-key", null);

            assertThat(inFlight).isEmpty();
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
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            try {
                executor.submit(key, () -> {
                    startedLatch.countDown();
                    try {
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
                assertThat(startedLatch.await(5, TimeUnit.SECONDS)).isTrue();
                CompletableFuture<Void> future = inFlight.get(key);
                assertThat(future).isNotNull();
                executor.cancel(key);
                assertThat(future).isCancelled();
                assertThat(inFlight).doesNotContainKey(key);
            } finally {
                release.countDown();
                assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            }
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
            CountDownLatch started = new CountDownLatch(keyCount);
            CountDownLatch release = new CountDownLatch(1);
            try {
                for (int i = 0; i < keyCount; i++) {
                    executor.submit("load-key-" + i, () -> {
                        started.countDown();
                        try {
                            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                    });
                }
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(executor.getActiveCount()).isEqualTo(keyCount);
                release.countDown();
                await().atMost(5, TimeUnit.SECONDS).untilAsserted(
                        () -> assertThat(executor.getActiveCount()).isZero());
            } finally {
                release.countDown();
            }
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
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submit("running-key", () -> {
                started.countDown();
                try {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                executor.shutdown();
                assertThat(executor.getActiveCount()).isZero();
            } finally {
                release.countDown();
            }
        }

        @Test
        @DisplayName("shutdown properly cleans up all resources")
        void shutdown_properlyCleansUpResources() throws Exception {
            ExecutorService testPool = Executors.newCachedThreadPool();
            ThreadPoolEarlyExpirationExecutor testExecutor = new ThreadPoolEarlyExpirationExecutor(
                    testPool,
                    new ConcurrentHashMap<>(),
                    null
            );

            try {
                CountDownLatch latch = new CountDownLatch(1);
                testExecutor.submit("cleanup-key", latch::countDown);
                assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

                testExecutor.shutdown();

                assertThat(testExecutor.getActiveCount()).isEqualTo(0);
            } finally {
                testExecutor.shutdown();
                assertThat(testPool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("retry tests")
    class RetryTests {

        @Test
        @DisplayName("succeeds after retries")
        void submit_failingTask_succeedsAfterRetries() throws InterruptedException {
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

            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(inFlight).isEmpty());

            // Should have attempted 3 times (initial + 2 retries)
            assertThat(attemptCount.get()).isEqualTo(3);
        }
    }
    @Test
    void exhaustedRetries_completeExceptionallyAndCleanUpWithMetrics() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ConcurrentHashMap<String, CompletableFuture<Void>> tasks = new ConcurrentHashMap<>();
        ThreadPoolEarlyExpirationExecutor tested = new ThreadPoolEarlyExpirationExecutor(pool, tasks, registry);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException last = new IllegalStateException("last failure");
        try {
            tested.submit("failure", () -> {
                started.countDown();
                try {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                attempts.incrementAndGet();
                throw last;
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> future = tasks.get("failure");
            release.countDown();
            assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCause(last);
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(tasks).isEmpty();
                assertThat(registry.get("prerefresh.completed").counter().count()).isEqualTo(1);
            });
            assertThat(attempts).hasValue(3);
            assertThat(registry.get("prerefresh.submitted").counter().count()).isEqualTo(1);
            assertThat(registry.get("prerefresh.cancelled").counter().count()).isZero();
        } finally {
            release.countDown();
            tested.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            registry.close();
        }
    }

}
