package io.github.davidhlp.spring.cache.redis.protection.breakdown;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

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
                .hasMessageContaining("Thread interrupted while acquiring distributed lock for key: test-key")
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
    // Caveat (intentionally documented, NOT asserted here): the underlying
    // DistributedLockManager derives leaseTimeSeconds via
    //     max(MIN_LEASE_TIME_SECONDS=10, timeoutSeconds * LEASE_TIMEOUT_MULTIPLIER=3)
    // Long.MAX_VALUE * 3 overflows to a negative long, so max(10, negative) = 10.
    // That is a separate concern (it lives in DistributedLockManager, not
    // SyncSupport) and is out of scope for this commit. The tests below only
    // prove SyncSupport's pass-through contract.
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
}
