package io.github.davidhlp.spring.cache.redis.protection.breakdown;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;

import io.lettuce.core.cluster.SlotHash;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DistributedLockManager Tests")
class DistributedLockManagerTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    @Mock
    private RedisProCacheProperties properties;

    @Mock
    private RedisProCacheProperties.SyncLockProperties syncLockProperties;

    @Mock
    private RedisProCacheProperties.RedisDeploymentProperties redisProperties;

    private DistributedLockManager lockManager;

    @BeforeEach
    void setUp() {
        when(properties.getSyncLock()).thenReturn(syncLockProperties);
        when(syncLockProperties.getPrefix()).thenReturn("cache:lock:");
        when(properties.getRedis()).thenReturn(redisProperties);
        when(redisProperties.getMode()).thenReturn("single");
        lockManager = new DistributedLockManager(redissonClient, properties);
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
        @DisplayName("wraps InterruptedException in RuntimeException when thread is interrupted during wait")
        void tryAcquire_interrupted_throwsRuntimeExceptionWithCause() throws InterruptedException {
            String key = "test-key";
            String lockKey = "cache:lock:" + key;
            mockGetLock(lockKey);
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                    .thenThrow(new InterruptedException("Thread interrupted"));

            assertThatThrownBy(() -> lockManager.tryAcquire(key, 5))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Interrupted while waiting for distributed lock on key: " + key)
                    .hasCauseInstanceOf(InterruptedException.class)
                    .hasRootCauseMessage("Thread interrupted");
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
    @DisplayName("buildLockKey / Cluster hash-tag tests (WS-1.2b)")
    class BuildLockKeyTests {

        @Test
        @DisplayName("non-cluster (single) mode: lock key is plain prefix + key, no hash-tag wrapping")
        void buildLockKey_singleMode_plainPrefix() {
            when(redisProperties.getMode()).thenReturn("single");
            assertThat(lockManager.buildLockKey("users:123")).isEqualTo("cache:lock:users:123");
        }

        @Test
        @DisplayName("sentinel mode behaves like single (no hash-tag wrapping)")
        void buildLockKey_sentinelMode_plainPrefix() {
            when(redisProperties.getMode()).thenReturn("sentinel");
            assertThat(lockManager.buildLockKey("users:123")).isEqualTo("cache:lock:users:123");
        }

        @Test
        @DisplayName("cluster mode + key without hash-tag: wraps key in {} to pin slot")
        void buildLockKey_cluster_noHashTag_wrapsKey() {
            when(redisProperties.getMode()).thenReturn("cluster");
            String cacheKey = "users:123";
            String lockKey = lockManager.buildLockKey(cacheKey);

            assertThat(lockKey).isEqualTo("cache:lock:{users:123}");
            // 权威校验:锁 key 与缓存 key 同 slot (lettuce SlotHash = Redis CLUSTER KEYSLOT 算法)
            assertThat(SlotHash.getSlot(lockKey)).isEqualTo(SlotHash.getSlot(cacheKey));
        }

        @Test
        @DisplayName("cluster mode + key with hash-tag: preserves key's hash-tag, same slot")
        void buildLockKey_cluster_withHashTag_preservesTag() {
            when(redisProperties.getMode()).thenReturn("cluster");
            String cacheKey = "{tenant1}:user:123";
            String lockKey = lockManager.buildLockKey(cacheKey);

            assertThat(lockKey).isEqualTo("cache:lock:{tenant1}:user:123");
            assertThat(SlotHash.getSlot(lockKey)).isEqualTo(SlotHash.getSlot(cacheKey));
        }

        @Test
        @DisplayName("cluster mode: many keys all produce same-slot lock keys (incl. cross-slot-prone prefix)")
        void buildLockKey_cluster_variousKeys_sameSlot() {
            when(redisProperties.getMode()).thenReturn("cluster");
            // 若不加 hash-tag,prefix 会使锁 key 落到与缓存 key 不同 slot;这里验证全部对齐
            String[] keys = {"simple", "a:b:c", "user:1001", "{order}:42:detail",
                    "no-hashtag-but-long-key:abc", "中文:键"};
            for (String cacheKey : keys) {
                String lockKey = lockManager.buildLockKey(cacheKey);
                assertThat(SlotHash.getSlot(lockKey))
                        .as("lock key must share slot with cache key: %s -> %s", cacheKey, lockKey)
                        .isEqualTo(SlotHash.getSlot(cacheKey));
            }
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

            verify(rLock, times(3)).unlock();
        }

        @Test
        @DisplayName("close retries unlock on failure up to 3 times")
        void close_unlockFails_retriesUpToThreeTimes() throws InterruptedException {
            String key = "test-key";
            String lockKey = "cache:lock:" + key;
            mockGetLock(lockKey);
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(rLock.isHeldByCurrentThread()).thenReturn(true);
            doThrow(new RuntimeException("Unlock failed"))
                    .doThrow(new RuntimeException("Unlock failed again"))
                    .doNothing()
                    .when(rLock).unlock();

            Optional<LockManager.LockHandle> result = lockManager.tryAcquire(key, 5);
            result.get().close();

            verify(rLock, times(3)).unlock();
        }

        @Test
        @DisplayName("close retries unlock and gives up after max retries")
        void close_unlockFails_givesUpAfterMaxRetries() throws InterruptedException {
            String key = "test-key";
            String lockKey = "cache:lock:" + key;
            mockGetLock(lockKey);
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(rLock.isHeldByCurrentThread()).thenReturn(true);
            doThrow(new RuntimeException("Unlock failed"))
                    .when(rLock).unlock();

            Optional<LockManager.LockHandle> result = lockManager.tryAcquire(key, 5);
            result.get().close();

            verify(rLock, times(3)).unlock();
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
