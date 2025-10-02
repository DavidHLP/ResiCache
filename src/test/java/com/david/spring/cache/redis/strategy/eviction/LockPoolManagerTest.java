package com.david.spring.cache.redis.strategy.eviction;

import static org.assertj.core.api.Assertions.*;

import com.david.spring.cache.redis.strategy.eviction.stats.LockPoolStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 锁池管理器测试 */
class LockPoolManagerTest {

    private LockPoolManager lockPool;

    @BeforeEach
    void setUp() {
        lockPool = new LockPoolManager(10, 5); // 小容量便于测试
    }

    @Test
    void testAcquireAndRelease() {
        // 获取锁
        LockWrapper lock1 = lockPool.acquire("key1");
        assertThat(lock1).isNotNull();
        assertThat(lockPool.size()).isEqualTo(1);

        // 再次获取相同key，应该返回同一个锁对象
        LockWrapper lock2 = lockPool.acquire("key1");
        assertThat(lock2).isSameAs(lock1);
        assertThat(lockPool.size()).isEqualTo(1);

        // 释放锁
        lockPool.release("key1");

        // 获取统计信息
        LockPoolStats stats = lockPool.getStats();
        assertThat(stats.totalAcquires()).isEqualTo(2);
        assertThat(stats.totalReleases()).isEqualTo(1);
        assertThat(stats.cacheHits()).isEqualTo(1);
        assertThat(stats.cacheMisses()).isEqualTo(1);
    }

    @Test
    void testLockReuse() {
        // 创建多个锁
        List<LockWrapper> locks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            locks.add(lockPool.acquire("key" + i));
        }

        assertThat(lockPool.size()).isEqualTo(5);

        // 再次获取，验证复用
        for (int i = 0; i < 5; i++) {
            LockWrapper lock = lockPool.acquire("key" + i);
            assertThat(lock).isSameAs(locks.get(i));
        }

        // 验证缓存命中率
        assertThat(lockPool.getHitRate()).isEqualTo(0.5); // 5次命中 / 10次总请求
    }

    @Test
    void testEvictionWhenFull() {
        // 填满 Active List (10个)
        for (int i = 0; i < 10; i++) {
            lockPool.acquire("active" + i);
        }

        // 填满 Inactive List (5个) - 需要再添加5个触发降级
        for (int i = 0; i < 5; i++) {
            lockPool.acquire("inactive" + i);
        }

        LockPoolStats stats = lockPool.getStats();
        assertThat(stats.activeSize()).isEqualTo(10);
        assertThat(stats.inactiveSize()).isEqualTo(5);

        // 再添加一个，应该触发淘汰
        lockPool.acquire("new");

        stats = lockPool.getStats();
        assertThat(stats.totalEvictions()).isGreaterThan(0);
    }

    @Test
    void testCannotEvictLockedLock() throws InterruptedException {
        // 填满锁池
        for (int i = 0; i < 10; i++) {
            lockPool.acquire("key" + i);
        }

        // 获取并持有一个锁
        LockWrapper heldLock = lockPool.acquire("key0");
        heldLock.lock();

        try {
            // 继续添加新锁，触发淘汰
            for (int i = 0; i < 10; i++) {
                lockPool.acquire("new" + i);
            }

            // 验证被持有的锁没有被淘汰
            LockWrapper sameLock = lockPool.acquire("key0");
            assertThat(sameLock).isSameAs(heldLock);

        } finally {
            heldLock.unlock();
        }
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(
                    () -> {
                        try {
                            for (int i = 0; i < operationsPerThread; i++) {
                                String key = "thread" + threadId + "-key" + (i % 5);
                                LockWrapper lock = lockPool.acquire(key);
                                lock.lock();
                                try {
                                    // 模拟临界区操作
                                    Thread.sleep(1);
                                } finally {
                                    lock.unlock();
                                    lockPool.release(key);
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // 验证统计信息
        LockPoolStats stats = lockPool.getStats();
        assertThat(stats.totalAcquires()).isEqualTo(threadCount * operationsPerThread);
        assertThat(stats.totalReleases()).isEqualTo(threadCount * operationsPerThread);
        assertThat(stats.hitRate()).isGreaterThan(0.0);
    }

    @Test
    void testRemove() {
        LockWrapper lock = lockPool.acquire("key1");
        assertThat(lockPool.size()).isEqualTo(1);

        lockPool.remove("key1");
        assertThat(lockPool.size()).isEqualTo(0);

        // 再次获取应该创建新锁
        LockWrapper newLock = lockPool.acquire("key1");
        assertThat(newLock).isNotSameAs(lock);
    }

    @Test
    void testClear() {
        for (int i = 0; i < 5; i++) {
            lockPool.acquire("key" + i);
        }

        assertThat(lockPool.size()).isEqualTo(5);

        lockPool.clear();
        assertThat(lockPool.size()).isEqualTo(0);

        LockPoolStats stats = lockPool.getStats();
        assertThat(stats.totalSize()).isEqualTo(0);
        assertThat(stats.activeSize()).isEqualTo(0);
        assertThat(stats.inactiveSize()).isEqualTo(0);
    }

    @Test
    void testStats() {
        // 执行一些操作
        lockPool.acquire("key1");
        lockPool.acquire("key1"); // 缓存命中
        lockPool.acquire("key2");
        lockPool.release("key1");

        LockPoolStats stats = lockPool.getStats();

        assertThat(stats.totalAcquires()).isEqualTo(3);
        assertThat(stats.totalReleases()).isEqualTo(1);
        assertThat(stats.cacheHits()).isEqualTo(1);
        assertThat(stats.cacheMisses()).isEqualTo(2);
        assertThat(stats.hitRate()).isEqualTo(1.0 / 3.0);

        // 验证 toString 不抛异常
        assertThat(stats.toString()).contains("LockPoolStats");
    }

    @Test
    void testMemoryEstimation() {
        // 创建一个更大的锁池用于测试
        LockPoolManager largeLockPool = new LockPoolManager(100, 50);

        for (int i = 0; i < 100; i++) {
            largeLockPool.acquire("key" + i);
        }

        LockPoolStats stats = largeLockPool.getStats();
        long memoryKB = stats.estimatedMemoryKB();

        // 验证内存估算合理（100个锁 * 48字节 / 1024 ≈ 4.6KB）
        assertThat(memoryKB).isBetween(4L, 6L);
    }

    @Test
    void testUtilizationMetrics() {
        // 填充一半
        for (int i = 0; i < 5; i++) {
            lockPool.acquire("key" + i);
        }

        LockPoolStats stats = lockPool.getStats();

        // 活跃使用率应该约为 50%
        assertThat(stats.activeUtilization()).isBetween(0.4, 0.6);
        assertThat(stats.totalUtilization()).isGreaterThan(0.0);
    }

    @Test
    void testNullKeyHandling() {
        assertThatThrownBy(() -> lockPool.acquire(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");

        // release 和 remove 应该优雅处理 null
        assertThatCode(() -> lockPool.release(null)).doesNotThrowAnyException();
        assertThatCode(() -> lockPool.remove(null)).doesNotThrowAnyException();
    }
}
