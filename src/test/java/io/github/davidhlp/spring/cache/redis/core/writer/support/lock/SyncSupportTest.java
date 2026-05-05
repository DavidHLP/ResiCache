package io.github.davidhlp.spring.cache.redis.core.writer.support.lock;

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

    @Test
    @DisplayName("returns result when no distributed managers")
    void executeSync_noManagers_returnsResult() {
        SyncSupport noManagerSupport = new SyncSupport(new ArrayList<>());

        String result = noManagerSupport.executeSync("test-key", () -> "value", 5);

        assertThat(result).isEqualTo("value");
    }

    @Test
    @DisplayName("returns result when lock acquired successfully")
    void executeSync_lockAcquired_returnsResult() throws InterruptedException {
        when(lockManager.tryAcquire(anyString(), anyLong())).thenReturn(Optional.of(mock(LockManager.LockHandle.class)));

        SyncSupport syncSupport = new SyncSupport(new ArrayList<>(List.of(lockManager)));
        String result = syncSupport.executeSync("test-key", () -> "value", 5);

        assertThat(result).isEqualTo("value");
    }

    @Test
    @DisplayName("throws IllegalStateException when thread is interrupted during lock acquisition")
    void executeSync_interrupted_throwsIllegalStateException() throws InterruptedException {
        when(lockManager.tryAcquire(anyString(), anyLong()))
                .thenThrow(new InterruptedException("Thread interrupted"));

        SyncSupport syncSupport = new SyncSupport(new ArrayList<>(List.of(lockManager)));
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

        SyncSupport syncSupport = new SyncSupport(new ArrayList<>(List.of(lockManager)));
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

        SyncSupport syncSupport = new SyncSupport(new ArrayList<>(List.of(lockManager)));
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
}
