package io.github.davidhlp.spring.cache.redis.core.writer.support.refresh;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PreRefreshSupport 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PreRefreshSupport Tests")
class PreRefreshSupportTest {

    @Mock
    private PreRefreshExecutor executor;

    private PreRefreshSupport support;

    @BeforeEach
    void setUp() {
        support = new PreRefreshSupport(executor);
    }

    @Nested
    @DisplayName("submitAsyncRefresh tests")
    class SubmitAsyncRefreshTests {

        @Test
        @DisplayName("submits task to executor")
        void submitAsyncRefresh_validInputs_submitsToExecutor() {
            String key = "test-key";
            Runnable task = () -> {};

            support.submitAsyncRefresh(key, task);

            verify(executor).submit(eq(key), eq(task));
        }

        @Test
        @DisplayName("skips submission when key is null")
        void submitAsyncRefresh_nullKey_skipsSubmission() {
            support.submitAsyncRefresh(null, () -> {});

            verify(executor, never()).submit(any(), any());
        }

        @Test
        @DisplayName("skips submission when task is null")
        void submitAsyncRefresh_nullTask_skipsSubmission() {
            support.submitAsyncRefresh("test-key", null);

            verify(executor, never()).submit(any(), any());
        }

        @Test
        @DisplayName("submits successfully with CountDownLatch task")
        void submitAsyncRefresh_countDownLatchTask_executes() throws InterruptedException {
            String key = "latch-key";
            CountDownLatch latch = new CountDownLatch(1);

            doAnswer(inv -> {
                Runnable r = inv.getArgument(1);
                new Thread(r).start();
                return null;
            }).when(executor).submit(anyString(), any(Runnable.class));

            support.submitAsyncRefresh(key, latch::countDown);

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Nested
    @DisplayName("cancelAsyncRefresh tests")
    class CancelAsyncRefreshTests {

        @Test
        @DisplayName("cancels task for key")
        void cancelAsyncRefresh_validKey_cancelsTask() {
            String key = "cancel-key";

            support.cancelAsyncRefresh(key);

            verify(executor).cancel(eq(key));
        }

        @Test
        @DisplayName("handles null key gracefully")
        void cancelAsyncRefresh_nullKey_noError() {
            support.cancelAsyncRefresh(null);

            // Should not throw and should not call executor
            verify(executor, never()).cancel(any());
        }
    }

    @Nested
    @DisplayName("getThreadPoolStats tests")
    class GetThreadPoolStatsTests {

        @Test
        @DisplayName("returns stats from executor")
        void getThreadPoolStats_returnsExecutorStats() {
            String expectedStats = "PreRefreshThreadPool[active=0, poolSize=2, queueSize=0, completed=10]";
            when(executor.getStats()).thenReturn(expectedStats);

            String stats = support.getThreadPoolStats();

            assertThat(stats).isEqualTo(expectedStats);
        }
    }

    @Nested
    @DisplayName("getRefreshingKeyCount tests")
    class GetRefreshingKeyCountTests {

        @Test
        @DisplayName("returns active count from executor")
        void getRefreshingKeyCount_returnsActiveCount() {
            when(executor.getActiveCount()).thenReturn(5);

            int count = support.getRefreshingKeyCount();

            assertThat(count).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("shutdown tests")
    class ShutdownTests {

        @Test
        @DisplayName("calls executor shutdown")
        void shutdown_callsExecutorShutdown() {
            support.shutdown();

            verify(executor).shutdown();
        }
    }

    @Nested
    @DisplayName("concurrent operations tests")
    class ConcurrentOperationsTests {

        @Test
        @DisplayName("handles concurrent submit and cancel")
        void concurrentSubmitAndCancel_noErrors() throws InterruptedException {
            String key = "concurrent-key";
            AtomicBoolean executed = new AtomicBoolean(false);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(2);

            doAnswer(inv -> {
                Runnable r = inv.getArgument(1);
                new Thread(() -> {
                    try {
                        startLatch.await();
                        r.run();
                        executed.set(true);
                    } catch (Exception e) {
                        // ignore
                    } finally {
                        endLatch.countDown();
                    }
                }).start();
                return null;
            }).when(executor).submit(anyString(), any(Runnable.class));

            // Submit
            new Thread(() -> {
                try {
                    startLatch.countDown();
                    support.submitAsyncRefresh(key, () -> {});
                } catch (Exception e) {
                    // ignore
                } finally {
                    endLatch.countDown();
                }
            }).start();

            // Give submit time to start
            Thread.sleep(100);

            // Cancel while running
            support.cancelAsyncRefresh(key);

            assertThat(endLatch.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
