package io.github.davidhlp.spring.cache.redis.cache;





import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RefreshRetryPolicy 单元测试
 *
 * <p>纯函数重试策略的独立测试(无需线程池),覆盖首次成功 / 重试后成功 / 全失败抛异常路径。
 * 这是 C06 抽出 RefreshRetryPolicy 的核心可测性收益。
 */
@DisplayName("RefreshRetryPolicy Tests")
class RefreshRetryPolicyTest {

    private final RefreshRetryPolicy policy = new RefreshRetryPolicy();

    @Test
    @DisplayName("succeeds on first attempt — no retry")
    void executeWithRetry_firstAttemptSuccess_noRetry() {
        AtomicInteger attempts = new AtomicInteger(0);

        policy.executeWithRetry("key", attempts::incrementAndGet);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("retries after failure and succeeds on second attempt")
    void executeWithRetry_failureThenSuccess_retriesAndSucceeds() {
        AtomicInteger attempts = new AtomicInteger(0);

        policy.executeWithRetry("key", () -> {
            if (attempts.incrementAndGet() < 2) {
                throw new RuntimeException("transient failure");
            }
        });

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("throws RuntimeException after exhausting all retry attempts")
    void executeWithRetry_allFail_throwsAfterMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() -> policy.executeWithRetry("key", () -> {
            attempts.incrementAndGet();
            throw new RuntimeException("always fails");
        }))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Pre-refresh failed");

        assertThat(attempts.get()).isEqualTo(RefreshRetryPolicy.MAX_RETRY_COUNT);
    }

    @Test
    @DisplayName("null key is tolerated — used only for log context")
    void executeWithRetry_nullKey_runsTaskWithoutThrowing() {
        AtomicInteger attempts = new AtomicInteger(0);

        policy.executeWithRetry(null, attempts::incrementAndGet);

        assertThat(attempts.get()).isEqualTo(1);
    }
    @Test
    void preInterruptedWorker_keepsFlagRetriesAndPreservesLastCause() throws Exception {
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                Thread.currentThread().interrupt();
                AtomicInteger attempts = new AtomicInteger();
                RuntimeException[] causes = {new IllegalStateException("first"),
                        new IllegalArgumentException("second"), new IllegalStateException("last")};
                try {
                    assertThatThrownBy(() -> policy.executeWithRetry("interrupted", () -> {
                        assertThat(Thread.currentThread().isInterrupted()).isTrue();
                        throw causes[attempts.getAndIncrement()];
                    })).hasCause(causes[2]);
                    assertThat(attempts).hasValue(3);
                    assertThat(Thread.currentThread().isInterrupted()).isTrue();
                } finally {
                    Thread.interrupted();
                }
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(worker.submit(() -> Thread.currentThread().isInterrupted())
                    .get(5, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

}
