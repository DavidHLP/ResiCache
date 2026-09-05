package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.LockManager;
import io.lettuce.core.cluster.SlotHash;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DistributedLockManager tests backed by a real Redis/Redisson instance.
 *
 * <p>Only the exception-injection tests use Mockito. Real lock acquisition,
 * mutual exclusion, release, and lease time assertions all exercise Redis.
 */
@ExtendWith(MockitoExtension.class)
@Import(TestRedisConfiguration.class)
@DisplayName("DistributedLockManager Tests (real Redis + Redisson)")
class DistributedLockManagerIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private RedisProCacheProperties properties;
    private DistributedLockManager lockManager;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushDb();

        properties = new RedisProCacheProperties();
        properties.getSyncLock().setPrefix("cache:lock:");
        properties.getRedis().setMode("single");
        lockManager = new DistributedLockManager(redissonClient, properties);
    }

    /**
     * Attempts an acquisition from a different JVM thread. Redisson locks are
     * reentrant for the owning thread, so a second thread is required to prove
     * mutual exclusion rather than accidentally exercising reentrancy.
     */
    private boolean tryAcquireOnAnotherThread(String key, long timeoutSeconds) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = executor.submit(() -> {
                Optional<LockManager.LockHandle> handle =
                        lockManager.tryAcquire(key, timeoutSeconds);
                if (handle.isEmpty()) {
                    return false;
                }
                try {
                    return true;
                } finally {
                    // Defensive cleanup if the lock unexpectedly becomes available.
                    handle.get().close();
                }
            });
            return result.get(timeoutSeconds + 2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Builds a manager wired to mocked I/O solely for deterministic failure
     * injection. Happy-path lock behavior is covered by the real manager above.
     */
    private DistributedLockManager faultInjectedManager(String key, RLock lock) {
        RedissonClient mockedClient = mock(RedissonClient.class);
        when(mockedClient.getLock(lockManager.buildLockKey(key))).thenReturn(lock);
        return new DistributedLockManager(mockedClient, properties);
    }

    private void assertLeaseTime(long timeoutSeconds, long expectedLeaseSeconds)
            throws InterruptedException {
        String key = "lease-time-" + timeoutSeconds;
        Optional<LockManager.LockHandle> result = lockManager.tryAcquire(key, timeoutSeconds);
        assertThat(result).isPresent();

        try {
            long remainingMillis =
                    redissonClient.getLock(lockManager.buildLockKey(key)).remainTimeToLive();
            // Redis reports the lease in milliseconds and a small amount of network/runtime
            // time elapses between acquisition and this assertion.
            assertThat(remainingMillis)
                    .isBetween(
                            expectedLeaseSeconds * 1_000L - 2_000L,
                            expectedLeaseSeconds * 1_000L);
        } finally {
            result.get().close();
        }
    }

    @Nested
    @DisplayName("tryAcquire tests")
    class TryAcquireTests {

        @Test
        @DisplayName("returns a handle when Redis lock acquisition succeeds")
        void tryAcquire_lockAcquired_returnsHandle() throws InterruptedException {
            Optional<LockManager.LockHandle> result = lockManager.tryAcquire("test-key", 5);

            assertThat(result).isPresent();
            assertThat(result.get()).isInstanceOf(LockManager.LockHandle.class);
            result.get().close();
        }

        @Test
        @DisplayName("returns empty for a second thread while the Redis lock is held")
        void tryAcquire_lockHeldByAnotherThread_returnsEmpty() throws Exception {
            String key = "exclusive-key";
            Optional<LockManager.LockHandle> first = lockManager.tryAcquire(key, 5);
            assertThat(first).isPresent();

            try {
                assertThat(tryAcquireOnAnotherThread(key, 1)).isFalse();
            } finally {
                first.get().close();
            }
        }

        @Test
        @DisplayName("wraps InterruptedException in RuntimeException when waiting is interrupted")
        void tryAcquire_interrupted_throwsRuntimeExceptionWithCause() throws InterruptedException {
            String key = "interrupted-key";
            RLock mockedLock = mock(RLock.class);
            DistributedLockManager faultManager = faultInjectedManager(key, mockedLock);
            when(mockedLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                    .thenThrow(new InterruptedException("Thread interrupted"));

            try {
                assertThatThrownBy(() -> faultManager.tryAcquire(key, 5))
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining(
                                "Interrupted while waiting for distributed lock on key: " + key)
                        .hasCauseInstanceOf(InterruptedException.class)
                        .hasRootCauseMessage("Thread interrupted");
            } finally {
                // DistributedLockManager deliberately restores the interrupted flag.
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("uses the configured lock key prefix in Redis")
        void tryAcquire_usesCorrectLockKeyPrefix() throws InterruptedException {
            String key = "my:custom:key";
            String lockKey = "cache:lock:" + key;
            Optional<LockManager.LockHandle> result = lockManager.tryAcquire(key, 5);

            assertThat(result).isPresent();
            try {
                assertThat(redissonClient.getKeys().countExists(lockKey)).isEqualTo(1L);
            } finally {
                result.get().close();
            }
        }
    }

    @Nested
    @DisplayName("buildLockKey / Cluster hash-tag tests")
    class BuildLockKeyTests {

        @Test
        @DisplayName("non-cluster (single) mode uses plain prefix + key")
        void buildLockKey_singleMode_plainPrefix() {
            properties.getRedis().setMode("single");

            assertThat(lockManager.buildLockKey("users:123"))
                    .isEqualTo("cache:lock:users:123");
        }

        @Test
        @DisplayName("sentinel mode uses plain prefix + key")
        void buildLockKey_sentinelMode_plainPrefix() {
            properties.getRedis().setMode("sentinel");

            assertThat(lockManager.buildLockKey("users:123"))
                    .isEqualTo("cache:lock:users:123");
        }

        @Test
        @DisplayName("cluster mode wraps a key without a hash-tag")
        void buildLockKey_cluster_noHashTag_wrapsKey() {
            properties.getRedis().setMode("cluster");
            String cacheKey = "users:123";

            String lockKey = lockManager.buildLockKey(cacheKey);

            assertThat(lockKey).isEqualTo("cache:lock:{users:123}");
            assertThat(SlotHash.getSlot(lockKey)).isEqualTo(SlotHash.getSlot(cacheKey));
        }

        @Test
        @DisplayName("cluster mode preserves a key's existing hash-tag")
        void buildLockKey_cluster_withHashTag_preservesTag() {
            properties.getRedis().setMode("cluster");
            String cacheKey = "{tenant1}:user:123";

            String lockKey = lockManager.buildLockKey(cacheKey);

            assertThat(lockKey).isEqualTo("cache:lock:{tenant1}:user:123");
            assertThat(SlotHash.getSlot(lockKey)).isEqualTo(SlotHash.getSlot(cacheKey));
        }

        @Test
        @DisplayName("cluster mode keeps varied cache keys in the same Redis slot")
        void buildLockKey_cluster_variousKeys_sameSlot() {
            properties.getRedis().setMode("cluster");
            String[] keys = {
                    "simple",
                    "a:b:c",
                    "user:1001",
                    "{order}:42:detail",
                    "no-hashtag-but-long-key:abc",
                    "中文:键"
            };

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
        @DisplayName("uses the minimum ten-second lease for a one-second timeout")
        void leaseTime_smallTimeout_usesMinLeaseTime() throws InterruptedException {
            assertLeaseTime(1, 10);
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
        @DisplayName("uses max(minimum lease, timeout multiplied by three)")
        void leaseTime_variousTimeouts_calculatesCorrectly(
                long timeoutSeconds, long expectedLeaseSeconds) throws InterruptedException {
            assertLeaseTime(timeoutSeconds, expectedLeaseSeconds);
        }

        @Test
        @DisplayName("uses timeout multiplied by three above the minimum lease")
        void leaseTime_largeTimeout_usesMultiplier() throws InterruptedException {
            assertLeaseTime(10, 30);
        }
    }

    @Nested
    @DisplayName("RedissonLockHandle close() tests")
    class RedissonLockHandleCloseTests {

        @Test
        @DisplayName("close releases the real Redis lock")
        void close_lockHeldByCurrentThread_releases() throws InterruptedException {
            String key = "release-key";
            Optional<LockManager.LockHandle> result = lockManager.tryAcquire(key, 5);
            assertThat(result).isPresent();

            result.get().close();

            Optional<LockManager.LockHandle> reacquired = lockManager.tryAcquire(key, 5);
            assertThat(reacquired).isPresent();
            reacquired.get().close();
        }

        @Test
        @DisplayName("close only releases a real Redis lock once")
        void close_calledMultipleTimes_releasesOnce() throws InterruptedException {
            String key = "multi-close-key";
            Optional<LockManager.LockHandle> result = lockManager.tryAcquire(key, 5);
            assertThat(result).isPresent();
            LockManager.LockHandle handle = result.get();

            handle.close();
            handle.close();
            handle.close();

            Optional<LockManager.LockHandle> reacquired = lockManager.tryAcquire(key, 5);
            assertThat(reacquired).isPresent();
            reacquired.get().close();
        }

        @Test
        @DisplayName("close handles unlock exceptions with three attempts")
        void close_unlockThrowsException_handledGracefully() throws InterruptedException {
            String key = "unlock-failure-key";
            RLock mockedLock = mock(RLock.class);
            DistributedLockManager faultManager = faultInjectedManager(key, mockedLock);
            when(mockedLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(mockedLock.isHeldByCurrentThread()).thenReturn(true);
            doThrow(new RuntimeException("Unlock failed")).when(mockedLock).unlock();

            Optional<LockManager.LockHandle> result = faultManager.tryAcquire(key, 5);
            assertThat(result).isPresent();
            result.get().close();

            verify(mockedLock, times(3)).unlock();
        }

        @Test
        @DisplayName("close retries unlock and succeeds on the third attempt")
        void close_unlockFails_retriesUpToThreeTimes() throws InterruptedException {
            String key = "unlock-retry-key";
            RLock mockedLock = mock(RLock.class);
            DistributedLockManager faultManager = faultInjectedManager(key, mockedLock);
            when(mockedLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(mockedLock.isHeldByCurrentThread()).thenReturn(true);
            doThrow(new RuntimeException("Unlock failed"))
                    .doThrow(new RuntimeException("Unlock failed again"))
                    .doNothing()
                    .when(mockedLock)
                    .unlock();

            Optional<LockManager.LockHandle> result = faultManager.tryAcquire(key, 5);
            assertThat(result).isPresent();
            result.get().close();

            verify(mockedLock, times(3)).unlock();
        }

        @Test
        @DisplayName("close gives up after the maximum unlock retries")
        void close_unlockFails_givesUpAfterMaxRetries() throws InterruptedException {
            String key = "unlock-give-up-key";
            RLock mockedLock = mock(RLock.class);
            DistributedLockManager faultManager = faultInjectedManager(key, mockedLock);
            when(mockedLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(mockedLock.isHeldByCurrentThread()).thenReturn(true);
            doThrow(new RuntimeException("Unlock failed")).when(mockedLock).unlock();

            Optional<LockManager.LockHandle> result = faultManager.tryAcquire(key, 5);
            assertThat(result).isPresent();
            result.get().close();

            verify(mockedLock, times(3)).unlock();
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
