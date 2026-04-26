package io.github.davidhlp.spring.cache.redis.core.writer.support.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DistributedLockManager 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DistributedLockManager Tests")
class DistributedLockManagerTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    private DistributedLockManager lockManager;

    @BeforeEach
    void setUp() {
        lockManager = new DistributedLockManager(redissonClient);
    }

    private void mockGetLock(String lockKey) {
        when(redissonClient.getLock(lockKey)).thenReturn(rLock);
    }

    @Nested
    @DisplayName("tryAcquire tests")
    class TryAcquireTests {

        @Test
        @DisplayName("returns empty when lock acquisition fails")
        void tryAcquire_lockNotAcquired_returnsEmpty() throws InterruptedException {
            String key = "test-key";
            String lockKey = "cache:lock:" + key;
            mockGetLock(lockKey);
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

            Optional<LockManager.LockHandle> result = lockManager.tryAcquire(key, 5);

            assertThat(result).isEmpty();
            verify(rLock).tryLock(eq(5L), anyLong(), eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("returns lock handle when lock acquired successfully")
        void tryAcquire_lockAcquired_returnsHandle() throws InterruptedException {
            String key = "test-key";
            String lockKey = "cache:lock:" + key;
            mockGetLock(lockKey);
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);

            Optional<LockManager.LockHandle> result = lockManager.tryAcquire(key, 5);

            assertThat(result).isPresent();
            assertThat(result.get()).isInstanceOf(LockManager.LockHandle.class);
        }

        @Test
        @DisplayName("throws InterruptedException when thread is interrupted during wait")
        void tryAcquire_interrupted_throwsException() throws InterruptedException {
            String key = "test-key";
            String lockKey = "cache:lock:" + key;
            mockGetLock(lockKey);
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                    .thenThrow(new InterruptedException("Thread interrupted"));

            assertThatThrownBy(() -> lockManager.tryAcquire(key, 5))
                    .isInstanceOf(InterruptedException.class)
                    .hasMessage("Thread interrupted");
        }

        @Test
        @DisplayName("uses correct lock key prefix")
        void tryAcquire_usesCorrectLockKeyPrefix() throws InterruptedException {
            String key = "my:custom:key";
            String lockKey = "cache:lock:" + key;
            mockGetLock(lockKey);
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);

            lockManager.tryAcquire(key, 5);

            verify(redissonClient).getLock("cache:lock:my:custom:key");
        }
    }

    @Nested
    @DisplayName("leaseTime calculation tests")
    class LeaseTimeCalculationTests {

        @Test
        @DisplayName("leaseTime is MIN_LEASE_TIME_SECONDS when timeout is very small")
        void leaseTime_smallTimeout_usesMinLeaseTime() throws InterruptedException {
            String key = "test-key";
            String lockKey = "cache:lock:" + key;
            mockGetLock(lockKey);
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);

            lockManager.tryAcquire(key, 1);

            ArgumentCaptor<Long> leaseTimeCaptor = ArgumentCaptor.forClass(Long.class);
            verify(rLock).tryLock(anyLong(), leaseTimeCaptor.capture(), eq(TimeUnit.SECONDS));
            assertThat(leaseTimeCaptor.getValue()).isEqualTo(10L);
        }

        @ParameterizedTest
        @CsvSource({
            "1, 10",
            "2, 10",
            "3, 10",
            "4, 12",
            "5, 15",
            "10, 30",
            "20, 60"
        })
        @DisplayName("leaseTime calculation: max(MIN_LEASE_TIME_SECONDS, timeoutSeconds * 3)")
        void leaseTime_variousTimeouts_calculatesCorrectly(long timeoutSeconds, long expectedLeaseTime) throws InterruptedException {
            String key = "test-key";
            String lockKey = "cache:lock:" + key;
            mockGetLock(lockKey);
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);

            lockManager.tryAcquire(key, timeoutSeconds);

            ArgumentCaptor<Long> leaseTimeCaptor = ArgumentCaptor.forClass(Long.class);
            verify(rLock).tryLock(anyLong(), leaseTimeCaptor.capture(), eq(TimeUnit.SECONDS));
            assertThat(leaseTimeCaptor.getValue())
                    .as("timeout=%d should result in leaseTime=%d", timeoutSeconds, expectedLeaseTime)
                    .isEqualTo(expectedLeaseTime);
        }

        @Test
        @DisplayName("leaseTime is timeoutSeconds * 3 when greater than MIN_LEASE_TIME_SECONDS")
        void leaseTime_largeTimeout_usesMultiplier() throws InterruptedException {
            String key = "test-key";
            String lockKey = "cache:lock:" + key;
            mockGetLock(lockKey);
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);

            lockManager.tryAcquire(key, 10);

            ArgumentCaptor<Long> leaseTimeCaptor = ArgumentCaptor.forClass(Long.class);
            verify(rLock).tryLock(anyLong(), leaseTimeCaptor.capture(), eq(TimeUnit.SECONDS));
            assertThat(leaseTimeCaptor.getValue()).isEqualTo(30L);
        }
    }

    @Nested
    @DisplayName("RedissonLockHandle close() tests")
    class RedissonLockHandleCloseTests {

        @Test
        @DisplayName("close releases lock when held by current thread")
        void close_lockHeldByCurrentThread_releases() throws InterruptedException {
            String key = "test-key";
            String lockKey = "cache:lock:" + key;
            mockGetLock(lockKey);
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(rLock.isHeldByCurrentThread()).thenReturn(true);

            Optional<LockManager.LockHandle> result = lockManager.tryAcquire(key, 5);
            result.get().close();

            verify(rLock).unlock();
        }

        @Test
        @DisplayName("close does nothing when not held by current thread")
        void close_notHeldByCurrentThread_doesNothing() throws InterruptedException {
            String key = "test-key";
            String lockKey = "cache:lock:" + key;
            mockGetLock(lockKey);
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(rLock.isHeldByCurrentThread()).thenReturn(false);

            Optional<LockManager.LockHandle> result = lockManager.tryAcquire(key, 5);
            result.get().close();

            verify(rLock, never()).unlock();
        }

        @Test
        @DisplayName("close only releases once even if called multiple times")
        void close_calledMultipleTimes_releasesOnce() throws InterruptedException {
            String key = "test-key";
            String lockKey = "cache:lock:" + key;
            mockGetLock(lockKey);
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(rLock.isHeldByCurrentThread()).thenReturn(true);

            Optional<LockManager.LockHandle> result = lockManager.tryAcquire(key, 5);
            LockManager.LockHandle handle = result.get();
            handle.close();
            handle.close();
            handle.close();

            verify(rLock, times(1)).unlock();
        }

        @Test
        @DisplayName("close handles unlock exception gracefully")
        void close_unlockThrowsException_handledGracefully() throws InterruptedException {
            String key = "test-key";
            String lockKey = "cache:lock:" + key;
            mockGetLock(lockKey);
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(rLock.isHeldByCurrentThread()).thenReturn(true);
            doThrow(new RuntimeException("Unlock failed")).when(rLock).unlock();

            Optional<LockManager.LockHandle> result = lockManager.tryAcquire(key, 5);
            result.get().close();

            verify(rLock).unlock();
        }
    }

    @Nested
    @DisplayName("getOrder tests")
    class GetOrderTests {

        @Test
        @DisplayName("returns 0 as order")
        void getOrder_returnsZero() {
            assertThat(lockManager.getOrder()).isZero();
        }
    }
}
