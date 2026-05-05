package io.github.davidhlp.spring.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.core.writer.support.lock.DistributedLockManager;
import io.github.davidhlp.spring.cache.redis.core.writer.support.lock.LockManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@ActiveProfiles("integration-test")
@ContextConfiguration(classes = TestRedisConfiguration.class)
@DisplayName("Distributed Lock Integration Tests")
class DistributedLockIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RedisProCacheProperties properties;

    private DistributedLockManager lockManager;

    @BeforeEach
    void setUp() {
        lockManager = new DistributedLockManager(redissonClient, properties);
    }

    @Nested
    @DisplayName("Lock Acquisition")
    class LockAcquisitionTests {

        @Test
        @DisplayName("should acquire lock successfully")
        void acquireLockSuccessfully() throws InterruptedException {
            Optional<LockManager.LockHandle> handle = lockManager.tryAcquire("test-key-1", 5);

            assertThat(handle).isPresent();
            handle.get().close();
        }

        @Test
        @DisplayName("should release lock after close")
        void releaseLockAfterClose() throws InterruptedException {
            String key = "test-key-release";

            Optional<LockManager.LockHandle> handle1 = lockManager.tryAcquire(key, 5);
            assertThat(handle1).isPresent();
            handle1.get().close();

            Optional<LockManager.LockHandle> handle2 = lockManager.tryAcquire(key, 5);
            assertThat(handle2).isPresent();
            handle2.get().close();
        }

        @Test
        @DisplayName("should not acquire same lock twice from different threads")
        void lockExclusivityBetweenThreads() throws InterruptedException {
            String key = "exclusive-key";
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger acquiredCount = new AtomicInteger(0);

            Optional<LockManager.LockHandle> mainHandle = lockManager.tryAcquire(key, 5);
            assertThat(mainHandle).isPresent();

            Thread otherThread = new Thread(() -> {
                try {
                    latch.countDown();
                    Optional<LockManager.LockHandle> handle = lockManager.tryAcquire(key, 1);
                    if (handle.isPresent()) {
                        acquiredCount.incrementAndGet();
                        handle.get().close();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            otherThread.start();
            latch.await(2, TimeUnit.SECONDS);
            TimeUnit.MILLISECONDS.sleep(500);

            assertThat(acquiredCount.get()).isZero();
            mainHandle.get().close();
            otherThread.join(3000);
        }
    }

    @Nested
    @DisplayName("Concurrent Access")
    class ConcurrentAccessTests {

        @Test
        @DisplayName("should ensure only one thread accesses critical section")
        void singleThreadInCriticalSection() throws InterruptedException {
            String lockKey = "concurrent-test";
            int threadCount = 5;
            AtomicInteger concurrentAccessCount = new AtomicInteger(0);
            AtomicInteger maxConcurrentAccess = new AtomicInteger(0);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completeLatch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Optional<LockManager.LockHandle> handle = lockManager.tryAcquire(lockKey, 10);
                        if (handle.isPresent()) {
                            int current = concurrentAccessCount.incrementAndGet();
                            maxConcurrentAccess.updateAndGet(max -> Math.max(max, current));
                            TimeUnit.MILLISECONDS.sleep(100);
                            concurrentAccessCount.decrementAndGet();
                            handle.get().close();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completeLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            completeLatch.await(15, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(maxConcurrentAccess.get()).isLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("should timeout when lock is held by another thread")
        void timeoutWhenLockHeld() throws InterruptedException {
            String lockKey = "timeout-test";

            Optional<LockManager.LockHandle> mainHandle = lockManager.tryAcquire(lockKey, 5);
            assertThat(mainHandle).isPresent();

            Optional<LockManager.LockHandle> secondHandle = lockManager.tryAcquire(lockKey, 1);
            assertThat(secondHandle).isEmpty();

            mainHandle.get().close();
        }
    }

    @Nested
    @DisplayName("Lock Handle Behavior")
    class LockHandleBehaviorTests {

        @Test
        @DisplayName("should handle multiple close calls gracefully")
        void multipleCloseCalls() throws InterruptedException {
            Optional<LockManager.LockHandle> handle = lockManager.tryAcquire("multi-close", 5);
            assertThat(handle).isPresent();

            handle.get().close();
            handle.get().close();
            handle.get().close();

            Optional<LockManager.LockHandle> newHandle = lockManager.tryAcquire("multi-close", 5);
            assertThat(newHandle).isPresent();
            newHandle.get().close();
        }

        @Test
        @DisplayName("should use correct lock key prefix")
        void lockKeyPrefix() throws InterruptedException {
            String key = "prefixed-key";
            Optional<LockManager.LockHandle> handle = lockManager.tryAcquire(key, 5);
            assertThat(handle).isPresent();
            handle.get().close();

            String lockKey = properties.getSyncLock().getPrefix() + key;
            assertThat(redissonClient.getKeys().countExists(lockKey)).isGreaterThanOrEqualTo(0);
        }
    }
}
