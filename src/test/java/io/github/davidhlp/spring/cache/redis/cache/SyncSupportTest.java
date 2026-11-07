package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.LockManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SyncSupport 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncSupport Tests")
class SyncSupportTest {

    @Mock
    private LockManager lockManager;

    private RedisProCacheProperties properties;

    @BeforeEach
    void setUp() {
        // 真实 POJO（默认 sync-lock.local-only=false → 空 managers 时 fail-fast）
        properties = new RedisProCacheProperties();
    }

    @Test
    @DisplayName("fails fast when no distributed managers and local-only disabled (default)")
    void executeSync_noManagers_failsFastByDefault() {
        SyncSupport noManagerSupport = new SyncSupport(new ArrayList<>(), properties);

        assertThatThrownBy(() -> noManagerSupport.executeSync("test-key", () -> "value", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sync=true 已声明但无分布式锁后端")
                .hasMessageContaining("local-only=true");
    }

    @Test
    @DisplayName("does not invoke loader when failing fast on missing distributed backend")
    void executeSync_noManagers_failsFast_doesNotInvokeLoader() {
        SyncSupport noManagerSupport = new SyncSupport(new ArrayList<>(), properties);
        AtomicBoolean loaderCalled = new AtomicBoolean(false);

        assertThatThrownBy(() -> noManagerSupport.executeSync("test-key", () -> {
            loaderCalled.set(true);
            return "value";
        }, 5)).isInstanceOf(IllegalStateException.class);

        assertThat(loaderCalled.get()).isFalse();
    }

    @Test
    @DisplayName("degrades to single-JVM sync when no managers but local-only explicitly enabled")
    void executeSync_noManagers_localOnly_degradesToJvm() {
        properties.getSyncLock().setLocalOnly(true);
        SyncSupport noManagerSupport = new SyncSupport(new ArrayList<>(), properties);

        String result = noManagerSupport.executeSync("test-key", () -> "value", 5);

        assertThat(result).isEqualTo("value");
    }

    @Test
    @DisplayName("protectionMode reports the actual sync-protection state for health reporting")
    void protectionMode_tracksBackendAndLocalOnly() {
        properties.getSyncLock().setLocalOnly(true);
        assertThat(new SyncSupport(new ArrayList<>(), properties).protectionMode())
                .isEqualTo(SyncSupport.ProtectionMode.LOCAL_ONLY);

        properties.getSyncLock().setLocalOnly(false);
        assertThat(new SyncSupport(new ArrayList<>(), properties).protectionMode())
                .isEqualTo(SyncSupport.ProtectionMode.FAIL_FAST);

        assertThat(new SyncSupport(new ArrayList<>(List.of(lockManager)), properties).protectionMode())
                .isEqualTo(SyncSupport.ProtectionMode.DISTRIBUTED);
    }

    @Test
    @DisplayName("returns result when lock acquired successfully")
    void executeSync_lockAcquired_returnsResult() throws InterruptedException {
        when(lockManager.tryAcquire(anyString(), anyLong())).thenReturn(Optional.of(mock(LockManager.LockHandle.class)));

        SyncSupport syncSupport = new SyncSupport(new ArrayList<>(List.of(lockManager)), properties);
        String result = syncSupport.executeSync("test-key", () -> "value", 5);

        assertThat(result).isEqualTo("value");
    }

    @Test
    @DisplayName("throws IllegalStateException when thread is interrupted during lock acquisition")
    void executeSync_interrupted_throwsIllegalStateException() throws InterruptedException {
        when(lockManager.tryAcquire(anyString(), anyLong()))
                .thenThrow(new InterruptedException("Thread interrupted"));

        SyncSupport syncSupport = new SyncSupport(new ArrayList<>(List.of(lockManager)), properties);
        assertThatThrownBy(() -> syncSupport.executeSync("test-key", () -> "value", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Thread interrupted while acquiring distributed lock: keyFingerprint=")
                .hasCauseInstanceOf(InterruptedException.class)
                .hasRootCauseMessage("Thread interrupted");
    }

    @Test
    @DisplayName("preserves interrupt status when InterruptedException occurs")
    void executeSync_interrupted_preservesInterruptStatus() throws InterruptedException {
        when(lockManager.tryAcquire(anyString(), anyLong()))
                .thenThrow(new InterruptedException("Thread interrupted"));

        SyncSupport syncSupport = new SyncSupport(new ArrayList<>(List.of(lockManager)), properties);
        try {
            syncSupport.executeSync("test-key", () -> "value", 5);
        } catch (IllegalStateException e) {
            // expected
        }

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }

    @Test
    @DisplayName("does not invoke loader when interrupted")
    void executeSync_interrupted_doesNotInvokeLoader() throws InterruptedException {
        when(lockManager.tryAcquire(anyString(), anyLong()))
                .thenThrow(new InterruptedException("Thread interrupted"));

        SyncSupport syncSupport = new SyncSupport(new ArrayList<>(List.of(lockManager)), properties);
        AtomicBoolean loaderCalled = new AtomicBoolean(false);

        try {
            syncSupport.executeSync("test-key", () -> {
                loaderCalled.set(true);
                return "value";
            }, 5);
        } catch (IllegalStateException e) {
            // expected
        } finally {
            Thread.interrupted();
        }

        assertThat(loaderCalled.get()).isFalse();
    }

    // ---------------------------------------------------------------------
    // Boundary tests for timeoutSeconds — round 39
    //
    // SyncSupport.executeSync currently passes timeoutSeconds through to the
    // LockManager without any validation. These tests lock in that
    // "transparent pass-through" contract for four boundary values so that
    // future hardening (e.g. fail-fast on negative / non-positive values) is
    // a conscious decision rather than a silent behavior change.
    //
    // The built-in manager uses watchdog renewal; waiting time does not set the lease.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("passes timeoutSeconds=0 through to LockManager (boundary: zero)")
    void executeSync_timeoutZero_passesToLockManager() throws InterruptedException {
        when(lockManager.tryAcquire(anyString(), anyLong()))
                .thenReturn(Optional.of(mock(LockManager.LockHandle.class)));

        SyncSupport syncSupport = new SyncSupport(new ArrayList<>(List.of(lockManager)), properties);
        String result = syncSupport.executeSync("test-key", () -> "value", 0);

        assertThat(result).isEqualTo("value");
        verify(lockManager).tryAcquire("test-key", 0L);
    }

    @Test
    @DisplayName("passes negative timeoutSeconds through to LockManager (boundary: negative)")
    void executeSync_timeoutNegative_passesToLockManager() throws InterruptedException {
        when(lockManager.tryAcquire(anyString(), anyLong()))
                .thenReturn(Optional.of(mock(LockManager.LockHandle.class)));

        SyncSupport syncSupport = new SyncSupport(new ArrayList<>(List.of(lockManager)), properties);
        String result = syncSupport.executeSync("test-key", () -> "value", -1);

        assertThat(result).isEqualTo("value");
        verify(lockManager).tryAcquire("test-key", -1L);
    }

    @Test
    @DisplayName("passes timeoutSeconds=1 (minimum positive) through to LockManager")
    void executeSync_timeoutOne_passesToLockManager() throws InterruptedException {
        when(lockManager.tryAcquire(anyString(), anyLong()))
                .thenReturn(Optional.of(mock(LockManager.LockHandle.class)));

        SyncSupport syncSupport = new SyncSupport(new ArrayList<>(List.of(lockManager)), properties);
        String result = syncSupport.executeSync("test-key", () -> "value", 1);

        assertThat(result).isEqualTo("value");
        verify(lockManager).tryAcquire("test-key", 1L);
    }

    @Test
    @DisplayName("passes timeoutSeconds=Long.MAX_VALUE through to LockManager (boundary: overflow risk lives in DistributedLockManager, not here)")
    void executeSync_timeoutMaxValue_passesToLockManager() throws InterruptedException {
        when(lockManager.tryAcquire(anyString(), anyLong()))
                .thenReturn(Optional.of(mock(LockManager.LockHandle.class)));

        SyncSupport syncSupport = new SyncSupport(new ArrayList<>(List.of(lockManager)), properties);
        String result = syncSupport.executeSync("test-key", () -> "value", Long.MAX_VALUE);

        assertThat(result).isEqualTo("value");
        verify(lockManager).tryAcquire("test-key", Long.MAX_VALUE);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void mixedProtocolsShareLocalQueueButNeverResults(boolean loaderLeader) throws Exception {
        var properties = new RedisProCacheProperties();
        properties.getSyncLock().setLocalOnly(true);
        var support = new SyncSupport(java.util.List.of(), properties);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var secondWork = new java.util.concurrent.CountDownLatch(1);
        java.util.function.Supplier<String> firstWork = () -> {
            entered.countDown();
            try {
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test gate timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return "first";
        };
        java.util.function.Supplier<Integer> otherWork = () -> {
            secondWork.countDown();
            return 42;
        };
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> loaderLeader
                    ? support.executeSync("same-key", firstWork, 5)
                    : support.executeSyncChain("same-key", firstWork, 5));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> loaderLeader
                    ? support.executeSyncChain("same-key", otherWork, 5)
                    : support.executeSync("same-key", otherWork, 5));
            try {
                assertThat(secondWork.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse();
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(second.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(42);
            assertThat(support.executeSync("same-key", () ->
                    support.executeSyncChain("same-key", () -> "nested", 0), 5)).isEqualTo("nested");
        } finally {
            release.countDown();
        }
    }
}
