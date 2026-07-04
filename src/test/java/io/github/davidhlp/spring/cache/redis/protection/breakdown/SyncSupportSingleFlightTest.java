package io.github.davidhlp.spring.cache.redis.protection.breakdown;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SyncSupport single-flight 并发测试 (ADR-0042).
 *
 * <p>覆盖 {@code runAsFollower} / 重入 fast-path —— 这是 ADR-0042 把 per-key
 * {@code synchronized(monitor)} 换成 {@code CompletableFuture} single-flight 后的
 * <b>新代码路径</b>,既有 {@code SyncSupportTest} 只覆盖单线程 leader 边界。
 *
 * <p>断言契约:
 * <ul>
 *   <li>同 key 高并发:loader 只调一次(leader 独占回源),所有 follower 共享 leader 结果</li>
 *   <li>leader 失败:follower 收到相同异常(failure-propagation 语义改变,ADR-0042)</li>
 *   <li>重入:同线程同 key 嵌套 executeSync 走 fast-path,不死锁(future 不可重入陷阱)</li>
 *   <li>follower 超时:leader 未完成时,follower 按时超时</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncSupport Single-Flight Concurrent Tests (ADR-0042)")
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
        ConcurrentLinkedQueue<Object> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < n; i++) {
            ex.submit(() -> {
                try {
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
        Thread.sleep(200); // 给 follower 足够时间 putIfAbsent 后 join leader future
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

        for (int i = 0; i < n; i++) {
            ex.submit(() -> {
                try {
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
        Thread.sleep(200);
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
        Thread.sleep(200); // 确保 leader 的 future 已发布

        long start = System.nanoTime();
        assertThatThrownBy(() -> support.executeSync("slow-key", () -> "X", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Timed out after 1");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
                .as("follower should wait ~1s, not return immediately or block forever")
                .isBetween(900L, 3000L);

        leaderProceed.countDown(); // 放行 leader,允许其完成 + 清理 in-flight slot
        ex.shutdown();
        assertThat(ex.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
}
