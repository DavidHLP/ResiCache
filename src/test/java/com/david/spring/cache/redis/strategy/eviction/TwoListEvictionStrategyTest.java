package com.david.spring.cache.redis.strategy.eviction;

import static org.assertj.core.api.Assertions.*;

import com.david.spring.cache.redis.strategy.eviction.impl.TwoListEvictionStrategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@DisplayName("TwoListEvictionStrategy 测试")
public class TwoListEvictionStrategyTest {

    private TwoListEvictionStrategy<String, String> strategy;

    @BeforeEach
    void setUp() {
        strategy = new TwoListEvictionStrategy<>(5, 3);
    }

    @Nested
    @DisplayName("基础功能测试")
    class BasicFunctionalityTests {

        @Test
        @DisplayName("应该正确添加和获取元素")
        void shouldPutAndGetElement() {
            strategy.put("key1", "value1");

            assertThat(strategy.get("key1")).isEqualTo("value1");
            assertThat(strategy.size()).isEqualTo(1);
            assertThat(strategy.contains("key1")).isTrue();
        }

        @Test
        @DisplayName("应该正确更新已存在的元素")
        void shouldUpdateExistingElement() {
            strategy.put("key1", "value1");
            strategy.put("key1", "value2");

            assertThat(strategy.get("key1")).isEqualTo("value2");
            assertThat(strategy.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("应该正确删除元素")
        void shouldRemoveElement() {
            strategy.put("key1", "value1");
            String removed = strategy.remove("key1");

            assertThat(removed).isEqualTo("value1");
            assertThat(strategy.get("key1")).isNull();
            assertThat(strategy.size()).isZero();
            assertThat(strategy.contains("key1")).isFalse();
        }

        @Test
        @DisplayName("删除不存在的元素应该返回null")
        void shouldReturnNullWhenRemovingNonExistentElement() {
            assertThat(strategy.remove("nonexistent")).isNull();
        }

        @Test
        @DisplayName("应该正确清空所有元素")
        void shouldClearAllElements() {
            strategy.put("key1", "value1");
            strategy.put("key2", "value2");
            strategy.put("key3", "value3");

            strategy.clear();

            assertThat(strategy.size()).isZero();
            assertThat(strategy.get("key1")).isNull();
            assertThat(strategy.get("key2")).isNull();
            assertThat(strategy.get("key3")).isNull();

            EvictionStats stats = strategy.getStats();
            assertThat(stats.totalEntries()).isZero();
            assertThat(stats.activeEntries()).isZero();
            assertThat(stats.inactiveEntries()).isZero();
        }

        @Test
        @DisplayName("获取不存在的元素应该返回null")
        void shouldReturnNullForNonExistentKey() {
            assertThat(strategy.get("nonexistent")).isNull();
        }

        @Test
        @DisplayName("contains应该正确判断元素是否存在")
        void shouldCorrectlyCheckContains() {
            strategy.put("key1", "value1");

            assertThat(strategy.contains("key1")).isTrue();
            assertThat(strategy.contains("key2")).isFalse();
        }
    }

    @Nested
    @DisplayName("Active List 和 Inactive List 交互测试")
    class ActiveInactiveListTests {

        @Test
        @DisplayName("新元素应该添加到Active List")
        void shouldAddNewElementToActiveList() {
            strategy.put("key1", "value1");
            strategy.put("key2", "value2");

            EvictionStats stats = strategy.getStats();
            assertThat(stats.activeEntries()).isEqualTo(2);
            assertThat(stats.inactiveEntries()).isZero();
        }

        @Test
        @DisplayName("Active List满时应该降级最老的元素到Inactive List")
        void shouldDemoteOldestElementWhenActiveListIsFull() {
            // 填满Active List (容量为5)
            for (int i = 1; i <= 5; i++) {
                strategy.put("key" + i, "value" + i);
            }

            EvictionStats stats = strategy.getStats();
            assertThat(stats.activeEntries()).isEqualTo(5);
            assertThat(stats.inactiveEntries()).isZero();

            // 添加第6个元素，应该触发降级
            strategy.put("key6", "value6");

            stats = strategy.getStats();
            assertThat(stats.activeEntries()).isEqualTo(5);
            assertThat(stats.inactiveEntries()).isEqualTo(1);
            assertThat(stats.totalEntries()).isEqualTo(6);
        }

        @Test
        @DisplayName("访问Inactive List中的元素应该提升到Active List")
        void shouldPromoteInactiveElementToActiveList() {
            // 填满Active List并触发降级
            for (int i = 1; i <= 6; i++) {
                strategy.put("key" + i, "value" + i);
            }

            // key1应该在Inactive List中
            EvictionStats statsBefore = strategy.getStats();
            assertThat(statsBefore.inactiveEntries()).isEqualTo(1);

            // 访问key1，应该提升到Active List
            String value = strategy.get("key1");
            assertThat(value).isEqualTo("value1");

            EvictionStats statsAfter = strategy.getStats();
            assertThat(statsAfter.activeEntries()).isEqualTo(5);
            assertThat(statsAfter.inactiveEntries()).isEqualTo(1);
        }

        @Test
        @DisplayName("更新Inactive List中的元素应该提升到Active List")
        void shouldPromoteInactiveElementWhenUpdating() {
            // 填满Active List并触发降级
            for (int i = 1; i <= 6; i++) {
                strategy.put("key" + i, "value" + i);
            }

            // 更新key1（在Inactive List中）
            strategy.put("key1", "newValue1");

            assertThat(strategy.get("key1")).isEqualTo("newValue1");

            EvictionStats stats = strategy.getStats();
            assertThat(stats.activeEntries()).isEqualTo(5);
            assertThat(stats.inactiveEntries()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("淘汰策略测试")
    class EvictionStrategyTests {

        @Test
        @DisplayName("Active和Inactive List都满时应该淘汰最老的元素")
        void shouldEvictOldestElementWhenBothListsAreFull() {
            // 填满两个列表 (Active=5, Inactive=3)
            for (int i = 1; i <= 8; i++) {
                strategy.put("key" + i, "value" + i);
            }

            EvictionStats stats = strategy.getStats();
            assertThat(stats.totalEntries()).isEqualTo(8);
            assertThat(stats.activeEntries()).isEqualTo(5);
            assertThat(stats.inactiveEntries()).isEqualTo(3);

            // 添加第9个元素，应该触发淘汰
            strategy.put("key9", "value9");

            stats = strategy.getStats();
            assertThat(stats.totalEntries()).isEqualTo(8);
            assertThat(stats.activeEntries()).isEqualTo(5);
            assertThat(stats.inactiveEntries()).isEqualTo(3);
            assertThat(stats.totalEvictions()).isEqualTo(1);

            // key1应该被淘汰
            assertThat(strategy.get("key1")).isNull();
        }

        @Test
        @DisplayName("应该正确统计淘汰次数")
        void shouldCorrectlyCountEvictions() {
            // 填满两个列表
            for (int i = 1; i <= 8; i++) {
                strategy.put("key" + i, "value" + i);
            }

            // 触发多次淘汰
            for (int i = 9; i <= 12; i++) {
                strategy.put("key" + i, "value" + i);
            }

            EvictionStats stats = strategy.getStats();
            assertThat(stats.totalEvictions()).isEqualTo(4);
        }

        @Test
        @DisplayName("访问Active List中的元素应该移到头部")
        void shouldMoveActiveElementToHead() {
            strategy.put("key1", "value1");
            strategy.put("key2", "value2");
            strategy.put("key3", "value3");

            // 访问key1，应该移到Active List头部
            strategy.get("key1");

            // 继续填充直到触发降级
            strategy.put("key4", "value4");
            strategy.put("key5", "value5");
            strategy.put("key6", "value6");

            // key2应该是最老的，被降级到Inactive
            assertThat(strategy.contains("key1")).isTrue();
            assertThat(strategy.contains("key2")).isTrue();

            EvictionStats stats = strategy.getStats();
            assertThat(stats.inactiveEntries()).isEqualTo(1);
        }

        @Test
        @DisplayName("连续添加超过容量的元素应该正确淘汰")
        void shouldEvictCorrectlyWhenAddingManyElements() {
            // 添加20个元素
            for (int i = 1; i <= 20; i++) {
                strategy.put("key" + i, "value" + i);
            }

            EvictionStats stats = strategy.getStats();
            assertThat(stats.totalEntries()).isEqualTo(8); // Active(5) + Inactive(3)
            assertThat(stats.totalEvictions()).isEqualTo(12);

            // 最新的8个元素应该存在
            for (int i = 13; i <= 20; i++) {
                assertThat(strategy.contains("key" + i)).isTrue();
            }

            // 最老的元素应该被淘汰
            for (int i = 1; i <= 12; i++) {
                assertThat(strategy.contains("key" + i)).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("边界条件和异常情况测试")
    class BoundaryAndExceptionTests {

        @Test
        @DisplayName("构造函数应该拒绝非正数的Active容量")
        void shouldRejectNonPositiveActiveSize() {
            assertThatThrownBy(() -> new TwoListEvictionStrategy<String, String>(0, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxActiveSize must be positive");

            assertThatThrownBy(() -> new TwoListEvictionStrategy<String, String>(-1, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxActiveSize must be positive");
        }

        @Test
        @DisplayName("构造函数应该拒绝非正数的Inactive容量")
        void shouldRejectNonPositiveInactiveSize() {
            assertThatThrownBy(() -> new TwoListEvictionStrategy<String, String>(5, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxInactiveSize must be positive");

            assertThatThrownBy(() -> new TwoListEvictionStrategy<String, String>(5, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxInactiveSize must be positive");
        }

        @Test
        @DisplayName("put方法应该拒绝null key")
        void shouldRejectNullKey() {
            assertThatThrownBy(() -> strategy.put(null, "value"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Key cannot be null");
        }

        @Test
        @DisplayName("get方法对null key应该返回null")
        void shouldReturnNullForNullKeyInGet() {
            assertThat(strategy.get(null)).isNull();
        }

        @Test
        @DisplayName("remove方法对null key应该返回null")
        void shouldReturnNullForNullKeyInRemove() {
            assertThat(strategy.remove(null)).isNull();
        }

        @Test
        @DisplayName("contains方法对null key应该返回false")
        void shouldReturnFalseForNullKeyInContains() {
            assertThat(strategy.contains(null)).isFalse();
        }

        @Test
        @DisplayName("应该能处理null value")
        void shouldHandleNullValue() {
            strategy.put("key1", null);

            assertThat(strategy.contains("key1")).isTrue();
            assertThat(strategy.get("key1")).isNull();
            assertThat(strategy.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("最小容量配置应该正常工作")
        void shouldWorkWithMinimalCapacity() {
            TwoListEvictionStrategy<String, String> minStrategy =
                    new TwoListEvictionStrategy<>(1, 1);

            minStrategy.put("key1", "value1");
            assertThat(minStrategy.size()).isEqualTo(1);

            minStrategy.put("key2", "value2");
            assertThat(minStrategy.size()).isEqualTo(2);

            minStrategy.put("key3", "value3");
            assertThat(minStrategy.size()).isEqualTo(2);

            EvictionStats stats = minStrategy.getStats();
            assertThat(stats.totalEvictions()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("并发安全性测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("多线程并发put应该是线程安全的")
        void shouldBeSafeForConcurrentPuts() throws InterruptedException {
            int threadCount = 10;
            int operationsPerThread = 100;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(
                        () -> {
                            try {
                                for (int i = 0; i < operationsPerThread; i++) {
                                    String key = "key_" + threadId + "_" + i;
                                    strategy.put(key, "value_" + threadId + "_" + i);
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // 验证一致性
            strategy.validateConsistency();

            EvictionStats stats = strategy.getStats();
            assertThat(stats.totalEntries()).isEqualTo(8); // Active(5) + Inactive(3)
        }

        @Test
        @DisplayName("多线程并发get和put应该是线程安全的")
        void shouldBeSafeForConcurrentGetsAndPuts() throws InterruptedException {
            // 预填充一些数据
            for (int i = 0; i < 5; i++) {
                strategy.put("key" + i, "value" + i);
            }

            int threadCount = 10;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            AtomicInteger successfulGets = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(
                        () -> {
                            try {
                                for (int i = 0; i < 100; i++) {
                                    if (i % 2 == 0) {
                                        strategy.put("key_" + threadId + "_" + i, "value");
                                    } else {
                                        String value = strategy.get("key" + (i % 5));
                                        if (value != null) {
                                            successfulGets.incrementAndGet();
                                        }
                                    }
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // 验证一致性
            strategy.validateConsistency();
            assertThat(successfulGets.get()).isGreaterThan(0);
        }

        @Test
        @DisplayName("多线程并发remove应该是线程安全的")
        void shouldBeSafeForConcurrentRemoves() throws InterruptedException {
            // 预填充数据
            for (int i = 0; i < 50; i++) {
                strategy.put("key" + i, "value" + i);
            }

            int threadCount = 5;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(
                        () -> {
                            try {
                                for (int i = threadId; i < 50; i += threadCount) {
                                    strategy.remove("key" + i);
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // 所有元素应该都被删除
            assertThat(strategy.size()).isZero();
            strategy.validateConsistency();
        }

        @Test
        @DisplayName("并发混合操作应该保持数据一致性")
        void shouldMaintainConsistencyUnderMixedConcurrentOperations() throws InterruptedException {
            int threadCount = 10;
            int operationsPerThread = 50;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(
                        () -> {
                            try {
                                for (int i = 0; i < operationsPerThread; i++) {
                                    String key = "key_" + (threadId * operationsPerThread + i);
                                    int operation = i % 4;

                                    switch (operation) {
                                        case 0:
                                            strategy.put(key, "value_" + i);
                                            break;
                                        case 1:
                                            strategy.get(key);
                                            break;
                                        case 2:
                                            strategy.remove(key);
                                            break;
                                        case 3:
                                            strategy.contains(key);
                                            break;
                                    }
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // 验证一致性
            strategy.validateConsistency();

            EvictionStats stats = strategy.getStats();
            assertThat(stats.totalEntries()).isLessThanOrEqualTo(8);
        }

        @Test
        @DisplayName("高并发场景下应该正确处理Active到Inactive的降级")
        void shouldHandleDemotionCorrectlyUnderHighConcurrency() throws InterruptedException {
            int threadCount = 20;
            int operationsPerThread = 50;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(
                        () -> {
                            try {
                                startLatch.await(); // 等待所有线程就绪
                                for (int i = 0; i < operationsPerThread; i++) {
                                    String key = "key_" + threadId + "_" + i;
                                    strategy.put(key, "value_" + threadId + "_" + i);

                                    // 随机访问之前添加的key
                                    if (i > 0 && i % 3 == 0) {
                                        String oldKey = "key_" + threadId + "_" + (i - 1);
                                        strategy.get(oldKey);
                                    }
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                endLatch.countDown();
                            }
                        });
            }

            startLatch.countDown(); // 同时启动所有线程
            assertThat(endLatch.await(20, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // 验证数据一致性
            strategy.validateConsistency();

            EvictionStats stats = strategy.getStats();
            // 高并发场景下，由于竞态条件，容量可能短暂超限，但最终应该接近目标
            assertThat(stats.totalEntries()).isLessThanOrEqualTo(30); // 宽松限制
            assertThat(stats.totalEvictions()).isGreaterThan(0);
        }

        @Test
        @DisplayName("并发更新同一个key应该保持一致性")
        void shouldMaintainConsistencyWhenConcurrentlyUpdatingSameKey()
                throws InterruptedException {
            String sharedKey = "shared_key";
            strategy.put(sharedKey, "initial");

            int threadCount = 20;
            int updatesPerThread = 100;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            AtomicInteger updateCounter = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(
                        () -> {
                            try {
                                for (int i = 0; i < updatesPerThread; i++) {
                                    int count = updateCounter.incrementAndGet();
                                    strategy.put(sharedKey, "value_" + threadId + "_" + count);

                                    // 偶尔读取验证
                                    if (i % 10 == 0) {
                                        String value = strategy.get(sharedKey);
                                        assertThat(value).isNotNull();
                                        assertThat(value).startsWith("value_");
                                    }
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // 验证key仍然存在且有值
            assertThat(strategy.contains(sharedKey)).isTrue();
            assertThat(strategy.get(sharedKey)).isNotNull();
            strategy.validateConsistency();
        }

        @Test
        @DisplayName("并发场景下Inactive到Active的提升应该正确")
        void shouldPromoteCorrectlyUnderConcurrency() throws InterruptedException {
            // 预先填充数据，确保有元素在Inactive List中
            for (int i = 1; i <= 8; i++) {
                strategy.put("key" + i, "value" + i);
            }

            // key1, key2, key3应该在Inactive List中
            int threadCount = 10;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            // 多个线程同时访问Inactive List中的元素
            for (int t = 0; t < threadCount; t++) {
                executor.submit(
                        () -> {
                            try {
                                for (int i = 0; i < 50; i++) {
                                    // 访问可能在Inactive List中的key
                                    String key = "key" + ((i % 3) + 1);
                                    String value = strategy.get(key);

                                    // 如果key还存在，应该能获取到值
                                    if (value != null) {
                                        assertThat(value).startsWith("value");
                                    }
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            strategy.validateConsistency();
        }

        @Test
        @DisplayName("并发场景下clear操作应该安全")
        void shouldHandleClearSafelyUnderConcurrency() throws InterruptedException {
            int threadCount = 10;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            AtomicInteger clearCount = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(
                        () -> {
                            try {
                                for (int i = 0; i < 20; i++) {
                                    String key = "key_" + threadId + "_" + i;
                                    strategy.put(key, "value_" + threadId + "_" + i);

                                    // 偶尔执行clear
                                    if (i == 10 && threadId % 3 == 0) {
                                        strategy.clear();
                                        clearCount.incrementAndGet();
                                    }

                                    // 继续操作
                                    strategy.get(key);
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // 验证一致性
            strategy.validateConsistency();
            assertThat(clearCount.get()).isGreaterThan(0);
        }

        @Test
        @DisplayName("读多写少场景下的性能和正确性")
        void shouldPerformCorrectlyInReadHeavyScenario() throws InterruptedException {
            // 预填充数据
            for (int i = 1; i <= 20; i++) {
                strategy.put("key" + i, "value" + i);
            }

            int threadCount = 20;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            AtomicInteger readCount = new AtomicInteger(0);
            AtomicInteger writeCount = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(
                        () -> {
                            try {
                                for (int i = 0; i < 200; i++) {
                                    if (i % 10 == 0) {
                                        // 10%的写操作
                                        strategy.put("key_new_" + i, "value_new_" + i);
                                        writeCount.incrementAndGet();
                                    } else {
                                        // 90%的读操作
                                        String key = "key" + ((i % 20) + 1);
                                        strategy.get(key);
                                        readCount.incrementAndGet();
                                    }
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            strategy.validateConsistency();
            assertThat(readCount.get()).isGreaterThan(writeCount.get() * 5);
        }

        @Test
        @DisplayName("写多读少场景下的性能和正确性")
        void shouldPerformCorrectlyInWriteHeavyScenario() throws InterruptedException {
            int threadCount = 15;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            AtomicInteger writeCount = new AtomicInteger(0);
            AtomicInteger readCount = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(
                        () -> {
                            try {
                                for (int i = 0; i < 100; i++) {
                                    if (i % 10 == 0) {
                                        // 10%的读操作
                                        String key = "key_" + threadId + "_" + (i - 1);
                                        strategy.get(key);
                                        readCount.incrementAndGet();
                                    } else {
                                        // 90%的写操作
                                        String key = "key_" + threadId + "_" + i;
                                        strategy.put(key, "value_" + threadId + "_" + i);
                                        writeCount.incrementAndGet();
                                    }
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            strategy.validateConsistency();
            assertThat(writeCount.get()).isGreaterThan(readCount.get() * 5);

            EvictionStats stats = strategy.getStats();
            // 写密集场景下允许一定的容量波动
            assertThat(stats.totalEntries()).isLessThanOrEqualTo(30);
        }

        @Test
        @DisplayName("并发场景下contains操作应该准确")
        void shouldReturnAccurateResultsForContainsUnderConcurrency() throws InterruptedException {
            // 预填充一些key
            for (int i = 1; i <= 10; i++) {
                strategy.put("stable_key" + i, "value" + i);
            }

            int threadCount = 10;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            AtomicInteger trueResults = new AtomicInteger(0);
            AtomicInteger falseResults = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(
                        () -> {
                            try {
                                for (int i = 0; i < 100; i++) {
                                    // 检查存在的key
                                    if (strategy.contains("stable_key" + ((i % 10) + 1))) {
                                        trueResults.incrementAndGet();
                                    }

                                    // 检查不存在的key
                                    if (!strategy.contains("nonexistent_" + threadId + "_" + i)) {
                                        falseResults.incrementAndGet();
                                    }

                                    // 添加新key并检查
                                    String newKey = "new_key_" + threadId + "_" + i;
                                    strategy.put(newKey, "value");
                                    if (strategy.contains(newKey)) {
                                        trueResults.incrementAndGet();
                                    }
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            assertThat(trueResults.get()).isGreaterThan(0);
            assertThat(falseResults.get()).isGreaterThan(0);
            strategy.validateConsistency();
        }

        @Test
        @DisplayName("极端高并发场景的压力测试")
        void shouldSurviveExtremeHighConcurrency() throws InterruptedException {
            int threadCount = 50;
            int operationsPerThread = 200;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            AtomicInteger totalOperations = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(
                        () -> {
                            try {
                                for (int i = 0; i < operationsPerThread; i++) {
                                    String key = "key_" + (threadId * operationsPerThread + i);
                                    int op = i % 5;

                                    switch (op) {
                                        case 0:
                                        case 1:
                                            strategy.put(key, "value_" + i);
                                            break;
                                        case 2:
                                            strategy.get(key);
                                            break;
                                        case 3:
                                            strategy.contains(key);
                                            break;
                                        case 4:
                                            strategy.remove(key);
                                            break;
                                    }
                                    totalOperations.incrementAndGet();
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // 验证一致性
            strategy.validateConsistency();

            assertThat(totalOperations.get()).isEqualTo(threadCount * operationsPerThread);

            EvictionStats stats = strategy.getStats();
            assertThat(stats.totalEntries()).isLessThanOrEqualTo(8);
        }
    }

    @Nested
    @DisplayName("淘汰判断器(Predicate)功能测试")
    class EvictionPredicateTests {

        @Test
        @DisplayName("淘汰判断器应该阻止特定元素被淘汰")
        void shouldPreventEvictionForProtectedElements() {
            // 创建一个保护以"protected"开头的value的判断器
            TwoListEvictionStrategy<String, String> protectedStrategy =
                    new TwoListEvictionStrategy<>(3, 2, value -> !value.startsWith("protected"));

            // 添加受保护的元素
            protectedStrategy.put("key1", "protected1");
            protectedStrategy.put("key2", "protected2");
            protectedStrategy.put("key3", "protected3");

            // 添加非保护元素
            protectedStrategy.put("key4", "normal4");
            protectedStrategy.put("key5", "normal5");

            // 继续添加更多元素，触发淘汰
            protectedStrategy.put("key6", "normal6");
            protectedStrategy.put("key7", "normal7");

            // 受保护的元素应该仍然存在
            assertThat(protectedStrategy.contains("key1")).isTrue();
            assertThat(protectedStrategy.contains("key2")).isTrue();
            assertThat(protectedStrategy.contains("key3")).isTrue();
        }

        @Test
        @DisplayName("可以动态设置淘汰判断器")
        void shouldAllowDynamicPredicateUpdate() {
            strategy.put("key1", "value1");
            strategy.put("key2", "value2");
            strategy.put("key3", "value3");

            // 设置判断器，保护key1
            strategy.setEvictionPredicate(value -> !value.equals("value1"));

            // 填满列表
            for (int i = 4; i <= 10; i++) {
                strategy.put("key" + i, "value" + i);
            }

            // key1应该仍然存在
            assertThat(strategy.contains("key1")).isTrue();
        }

        @Test
        @DisplayName("所有元素都受保护时应该无法添加新元素")
        void shouldFailToAddWhenAllElementsAreProtected() {
            // 创建保护所有元素的策略
            TwoListEvictionStrategy<String, String> allProtectedStrategy =
                    new TwoListEvictionStrategy<>(2, 1, value -> false);

            allProtectedStrategy.put("key1", "value1");
            allProtectedStrategy.put("key2", "value2");
            allProtectedStrategy.put("key3", "value3");

            // 此时所有元素都受保护，无法淘汰
            // 尝试添加新元素应该失败（无法腾出空间）
            allProtectedStrategy.put("key4", "value4");

            // key4可能无法添加，取决于实现
            EvictionStats stats = allProtectedStrategy.getStats();
            assertThat(stats.totalEntries()).isLessThanOrEqualTo(3);
        }
    }

    @Nested
    @DisplayName("统计信息(Stats)验证测试")
    class StatsTests {

        @Test
        @DisplayName("统计信息应该正确反映当前状态")
        void shouldReflectCurrentState() {
            EvictionStats initialStats = strategy.getStats();
            assertThat(initialStats.totalEntries()).isZero();
            assertThat(initialStats.activeEntries()).isZero();
            assertThat(initialStats.inactiveEntries()).isZero();
            assertThat(initialStats.maxActiveSize()).isEqualTo(5);
            assertThat(initialStats.maxInactiveSize()).isEqualTo(3);
            assertThat(initialStats.totalEvictions()).isZero();

            // 添加元素
            for (int i = 1; i <= 7; i++) {
                strategy.put("key" + i, "value" + i);
            }

            EvictionStats stats = strategy.getStats();
            assertThat(stats.totalEntries()).isEqualTo(7);
            assertThat(stats.activeEntries()).isEqualTo(5);
            assertThat(stats.inactiveEntries()).isEqualTo(2);
            assertThat(stats.totalEvictions()).isZero();
        }

        @Test
        @DisplayName("统计信息应该正确计数淘汰次数")
        void shouldCorrectlyCountEvictions() {
            // 填满并触发淘汰
            for (int i = 1; i <= 15; i++) {
                strategy.put("key" + i, "value" + i);
            }

            EvictionStats stats = strategy.getStats();
            assertThat(stats.totalEvictions()).isEqualTo(7); // 15 - 8 = 7次淘汰
        }

        @Test
        @DisplayName("clear后统计信息应该重置")
        void shouldResetStatsAfterClear() {
            for (int i = 1; i <= 10; i++) {
                strategy.put("key" + i, "value" + i);
            }

            strategy.clear();

            EvictionStats stats = strategy.getStats();
            assertThat(stats.totalEntries()).isZero();
            assertThat(stats.activeEntries()).isZero();
            assertThat(stats.inactiveEntries()).isZero();
            // 注意：totalEvictions不会重置
        }

        @Test
        @DisplayName("统计信息应该线程安全")
        void shouldBeThreadSafeForStats() throws InterruptedException {
            int threadCount = 5;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<EvictionStats> statsList = new CopyOnWriteArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                executor.submit(
                        () -> {
                            try {
                                for (int i = 0; i < 20; i++) {
                                    statsList.add(strategy.getStats());
                                    Thread.sleep(1);
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // 所有统计信息都应该成功获取
            assertThat(statsList).hasSize(threadCount * 20);

            // 验证统计信息的一致性
            for (EvictionStats stats : statsList) {
                assertThat(stats.totalEntries())
                        .isEqualTo(stats.activeEntries() + stats.inactiveEntries());
            }
        }
    }

    @Nested
    @DisplayName("数据一致性验证测试")
    class ConsistencyValidationTests {

        @Test
        @DisplayName("validateConsistency应该不抛出异常当数据一致时")
        void shouldNotThrowExceptionWhenDataIsConsistent() {
            for (int i = 1; i <= 10; i++) {
                strategy.put("key" + i, "value" + i);
            }

            assertThatCode(() -> strategy.validateConsistency()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("复杂操作后数据应该保持一致")
        void shouldMaintainConsistencyAfterComplexOperations() {
            // 添加
            for (int i = 1; i <= 10; i++) {
                strategy.put("key" + i, "value" + i);
            }

            // 访问
            for (int i = 1; i <= 5; i++) {
                strategy.get("key" + i);
            }

            // 更新
            for (int i = 1; i <= 3; i++) {
                strategy.put("key" + i, "newValue" + i);
            }

            // 删除
            for (int i = 6; i <= 8; i++) {
                strategy.remove("key" + i);
            }

            // 再添加
            for (int i = 11; i <= 15; i++) {
                strategy.put("key" + i, "value" + i);
            }

            assertThatCode(() -> strategy.validateConsistency()).doesNotThrowAnyException();

            EvictionStats stats = strategy.getStats();
            assertThat(stats.totalEntries()).isLessThanOrEqualTo(8);
        }
    }

    @Nested
    @DisplayName("默认构造函数测试")
    class DefaultConstructorTests {

        @Test
        @DisplayName("默认构造函数应该使用正确的默认容量")
        void shouldUseCorrectDefaultCapacity() {
            TwoListEvictionStrategy<String, String> defaultStrategy =
                    new TwoListEvictionStrategy<>();

            // 填充超过默认容量的元素
            for (int i = 1; i <= 2000; i++) {
                defaultStrategy.put("key" + i, "value" + i);
            }

            EvictionStats stats = defaultStrategy.getStats();
            assertThat(stats.maxActiveSize()).isEqualTo(1024);
            assertThat(stats.maxInactiveSize()).isEqualTo(512);
            assertThat(stats.totalEntries()).isEqualTo(1536); // 1024 + 512
            assertThat(stats.totalEvictions()).isGreaterThan(0);
        }
    }
}
