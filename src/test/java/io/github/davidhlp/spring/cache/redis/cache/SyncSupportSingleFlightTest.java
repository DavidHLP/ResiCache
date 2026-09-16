package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.LockManager;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SyncSupport single-flight 并发测试。
 *
 * <p>覆盖 {@code runAsFollower} / 重入 fast-path —— per-key single-flight 基于
 * {@code CompletableFuture},leader 独占回源,follower 共享结果。
 *
 * <p>断言契约:
 * <ul>
 *   <li>同 key 高并发:loader 只调一次(leader 独占回源),所有 follower 共享 leader 结果</li>
 *   <li>leader 失败:follower 收到相同异常(failure-propagation)</li>
 *   <li>重入:同线程同 key 嵌套 executeSync 走 fast-path,不死锁(future 不可重入陷阱)</li>
 *   <li>follower 超时:leader 未完成时,follower 按时超时</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncSupport Single-Flight Concurrent Tests")
class SyncSupportSingleFlightTest {

    @Mock
    private LockManager lockManager;

    private RedisProCacheProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RedisProCacheProperties();
    }

    /** Helper:阻塞等待 latch,最多 5 秒;中断时还原中断状态。 */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("latch did not count down within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting latch", e);
        }
    }

    @Test
    @DisplayName("concurrent followers: loader invoked exactly once, all share leader's result")
    void singleFlight_concurrentFollowers_loaderInvokedOnce_allShareResult() throws Exception {
        when(lockManager.tryAcquire(anyString(), anyLong()))
                .thenReturn(Optional.of(mock(LockManager.LockHandle.class)));
        SyncSupport support = new SyncSupport(List.of(lockManager), properties);

        AtomicInteger loaderCount = new AtomicInteger();
        CountDownLatch leaderStarted = new CountDownLatch(1);
        CountDownLatch leaderProceed = new CountDownLatch(1);
        int n = 10;
        ExecutorService ex = Executors.newFixedThreadPool(n);
        CountDownLatch done = new CountDownLatch(n);
        CountDownLatch callersReady = new CountDownLatch(n);
        ConcurrentLinkedQueue<Object> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < n; i++) {
            ex.submit(() -> {
                try {
                    callersReady.countDown();
                    Object r = support.executeSync("shared-key", () -> {
                        loaderCount.incrementAndGet();
                        leaderStarted.countDown();
                        await(leaderProceed); // 阻塞 leader,让其余 9 线程落到 follower 路径
                        return "VALUE";
                    }, 10);
                    results.add(r);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(leaderStarted.await(5, TimeUnit.SECONDS))
                .as("leader should enter loader").isTrue();
        assertThat(callersReady.await(5, TimeUnit.SECONDS))
                .as("all callers should reach executeSync").isTrue();
        assertThat(loaderCount.get())
                .as("followers must share the blocked leader").isEqualTo(1);
        leaderProceed.countDown(); // 放行 leader

        assertThat(done.await(5, TimeUnit.SECONDS))
                .as("all threads should complete").isTrue();
        ex.shutdown();

        assertThat(loaderCount.get())
                .as("loader invoked exactly once (single-flight)").isEqualTo(1);
        assertThat(results).hasSize(n);
        assertThat(results).allMatch(r -> "VALUE".equals(r));
    }

    @Test
    @DisplayName("executeExclusive:并发写各自执行(不 join),每笔工作都不被丢弃")
    void executeExclusive_concurrentWrites_eachRuns() throws Exception {
        when(lockManager.tryAcquire(anyString(), anyLong()))
                .thenReturn(Optional.of(mock(LockManager.LockHandle.class)));
        SyncSupport support = new SyncSupport(List.of(lockManager), properties);

        int n = 8;
        ExecutorService ex = Executors.newFixedThreadPool(n);
        CountDownLatch done = new CountDownLatch(n);
        ConcurrentLinkedQueue<String> written = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < n; i++) {
            final String value = "v" + i;
            ex.submit(() -> {
                try {
                    written.add(support.executeExclusive("write-key", () -> value, 10));
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(5, TimeUnit.SECONDS)).as("all writers complete").isTrue();
        ex.shutdown();

        assertThat(written)
                .as("独占执行:每个写线程都跑了自己的工作(single-flight 会只留一个)")
                .hasSize(n);
    }

    @Test
    @DisplayName("executeExclusive:local-only 并发写按 key 串行,每笔工作都保留")
    void executeExclusive_localOnly_serializesConcurrentWrites() throws Exception {
        properties.getSyncLock().setLocalOnly(true);
        SyncSupport support = new SyncSupport(List.of(), properties);

        int n = 4;
        ExecutorService ex = Executors.newFixedThreadPool(n);
        CountDownLatch done = new CountDownLatch(n);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> written = new ConcurrentLinkedQueue<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            final String value = "local-v" + i;
            ex.submit(() -> {
                try {
                    written.add(support.executeExclusive("local-write-key", () -> {
                        int current = active.incrementAndGet();
                        maxActive.updateAndGet(max -> Math.max(max, current));
                        firstEntered.countDown();
                        try {
                            if (current == 1) {
                                await(releaseFirst);
                            }
                            return value;
                        } finally {
                            active.decrementAndGet();
                        }
                    }, 10));
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
        releaseFirst.countDown();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        ex.shutdown();
        assertThat(written).hasSize(n);
        assertThat(maxActive).as("local-only writes must be mutually exclusive").hasValue(1);
    }

    @Test
    @DisplayName("executeExclusive:local-only 队列等待受 syncTimeout 约束,前驱卡死即失败(不无限阻塞)")
    void executeExclusive_localOnly_predecessorWaitHonorsTimeout() throws Exception {
        properties.getSyncLock().setLocalOnly(true);
        SyncSupport support = new SyncSupport(List.of(), properties);

        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService ex = Executors.newFixedThreadPool(2);

        try {
            ex.submit(() -> {
                support.executeExclusive("stalled-key", () -> {
                    firstEntered.countDown();
                    await(releaseFirst);
                    return "FIRST";
                }, 30);
                return null;
            });

            assertThat(firstEntered.await(5, TimeUnit.SECONDS))
                    .as("first caller holds the local-only queue").isTrue();

            Future<Throwable> queued = ex.submit(() -> {
                try {
                    support.executeExclusive("stalled-key", () -> "SECOND", 1);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Throwable failure = queued.get(5, TimeUnit.SECONDS);

            assertThat(failure)
                    .as("排队者必须按 syncTimeout 失败,绝不无限等待卡死的前驱")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Timed out after 1");
        } finally {
            releaseFirst.countDown();
        }

        // 超时者必须放行后继:队列不能卡在已放弃的条目上
        assertThat(support.executeExclusive("stalled-key", () -> "THIRD", 5))
                .as("超时后队列仍可用(超时路径已完成并移除自己的尾部条目)")
                .isEqualTo("THIRD");
        ex.shutdown();
        assertThat(ex.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("executeExclusive:local-only 超时 follower 退出后,前缀未 drain 时 newcomer 仍不得并发")
    void executeExclusive_localOnly_timedOutFollowerKeepsPrefixSerialized() throws Exception {
        properties.getSyncLock().setLocalOnly(true);
        SyncSupport support = new SyncSupport(List.of(), properties);
        String key = "local-prefix-drain-key";

        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch newcomerEntered = new CountDownLatch(1);
        CountDownLatch newcomerProceed = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        ExecutorService ex = Executors.newFixedThreadPool(3);

        try {
            Future<String> first = ex.submit(() -> support.executeExclusive(key, () -> {
                int current = active.incrementAndGet();
                maxActive.updateAndGet(max -> Math.max(max, current));
                firstEntered.countDown();
                try {
                    await(releaseFirst);
                    return "FIRST";
                } finally {
                    active.decrementAndGet();
                }
            }, 30));

            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> timedOut = ex.submit(() -> {
                try {
                    support.executeExclusive(key, () -> "SECOND", 0);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            assertThat(timedOut.get(5, TimeUnit.SECONDS))
                    .as("超时 follower 必须退出,但不能移除仍在运行前缀的队尾")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Timed out after 0");

            Future<String> newcomer = ex.submit(() -> support.executeExclusive(key, () -> {
                int current = active.incrementAndGet();
                maxActive.updateAndGet(max -> Math.max(max, current));
                newcomerEntered.countDown();
                try {
                    await(newcomerProceed);
                    return "THIRD";
                } finally {
                    active.decrementAndGet();
                }
            }, 5));

            assertThat(newcomerEntered.await(1, TimeUnit.SECONDS))
                    .as("前缀未 drain 时 newcomer 不得进入工作区")
                    .isFalse();

            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("FIRST");
            assertThat(newcomerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            newcomerProceed.countDown();
            assertThat(newcomer.get(5, TimeUnit.SECONDS)).isEqualTo("THIRD");
            assertThat(maxActive)
                    .as("local-only 同 key 在 follower 超时退出后仍必须串行")
                    .hasValue(1);
        } finally {
            releaseFirst.countDown();
            newcomerProceed.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("executeExclusive:同线程重入走 fast-path,不二次取锁(不死锁)")
    void executeExclusive_reentrant_runsInline() throws Exception {
        when(lockManager.tryAcquire(anyString(), anyLong()))
                .thenReturn(Optional.of(mock(LockManager.LockHandle.class)));
        SyncSupport support = new SyncSupport(List.of(lockManager), properties);

        String result = support.executeExclusive("nested-key", () ->
                support.executeExclusive("nested-key", () -> "inner", 10), 10);

        assertThat(result).isEqualTo("inner");
    }

    @Test
    @DisplayName("leader failure propagates to all followers as the same exception type/message")
    void singleFlight_leaderFails_followersReceiveSameException() throws Exception {
        when(lockManager.tryAcquire(anyString(), anyLong()))
                .thenReturn(Optional.of(mock(LockManager.LockHandle.class)));
        SyncSupport support = new SyncSupport(List.of(lockManager), properties);

        CountDownLatch leaderStarted = new CountDownLatch(1);
        CountDownLatch leaderProceed = new CountDownLatch(1);
        int n = 5;
        ExecutorService ex = Executors.newFixedThreadPool(n);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        CountDownLatch done = new CountDownLatch(n);
        CountDownLatch callersReady = new CountDownLatch(n);

        for (int i = 0; i < n; i++) {
            ex.submit(() -> {
                try {
                    callersReady.countDown();
                    support.executeSync("failing-key", () -> {
                        leaderStarted.countDown();
                        await(leaderProceed);
                        throw new IllegalStateException("DB DOWN");
                    }, 10);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(leaderStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(callersReady.await(5, TimeUnit.SECONDS))
                .as("all callers should reach executeSync").isTrue();
        leaderProceed.countDown();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        ex.shutdown();

        assertThat(errors).hasSize(n);
        assertThat(errors).allSatisfy(t ->
                assertThat(t).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("DB DOWN"));
    }

    @Test
    @DisplayName("reentrant nested executeSync (same key) takes fast-path — no deadlock")
    void singleFlight_reentrantNestedFastPath_noDeadlock() {
        // 无 distributedManagers + local-only → leader 不持分布式锁,直接 loader。
        // loader 内再嵌套 executeSync(同 key)模拟 chain 内 SyncLockHandler 重入。
        properties.getSyncLock().setLocalOnly(true);
        SyncSupport support = new SyncSupport(List.of(), properties);

        AtomicInteger nestedLoaderCount = new AtomicInteger();
        String result = support.executeSync("reentrant-key", () ->
                // 嵌套重入:future 不可重入,必须走 ThreadLocal fast-path,否则死锁
                support.executeSync("reentrant-key", () -> {
                    nestedLoaderCount.incrementAndGet();
                    return "NESTED-VALUE";
                }, 5), 5);

        assertThat(result).isEqualTo("NESTED-VALUE");
        assertThat(nestedLoaderCount.get())
                .as("nested loader executed via fast-path").isEqualTo(1);
    }

    @Test
    @DisplayName("follower times out when leader loader exceeds follower wait window")
    void singleFlight_followerTimeout_leaderStillRunning() throws Exception {
        // local-only 模式,leader loader 故意阻塞,follower 用 1s 短超时
        properties.getSyncLock().setLocalOnly(true);
        SyncSupport support = new SyncSupport(List.of(), properties);

        CountDownLatch leaderStarted = new CountDownLatch(1);
        CountDownLatch leaderProceed = new CountDownLatch(1);
        ExecutorService ex = Executors.newFixedThreadPool(2);

        ex.submit(() -> {
            support.executeSync("slow-key", () -> {
                leaderStarted.countDown();
                await(leaderProceed); // leader 阻塞 60s 窗口
                return "SLOW";
            }, 60);
            return null;
        });

        assertThat(leaderStarted.await(5, TimeUnit.SECONDS))
                .as("leader should hold the in-flight slot").isTrue();

        assertThatThrownBy(() -> support.executeSync("slow-key", () -> "X", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Timed out after 1");

        leaderProceed.countDown(); // 放行 leader,允许其完成 + 清理 in-flight slot
        ex.shutdown();
        assertThat(ex.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
}
