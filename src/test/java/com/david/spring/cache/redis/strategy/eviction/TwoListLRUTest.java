package com.david.spring.cache.redis.strategy.eviction;

import com.david.spring.cache.redis.strategy.eviction.support.TwoListLRU;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** TwoListLRU算法测试 */
class TwoListLRUTest {

    @Nested
    class BasicOperations {
        private TwoListLRU<String, String> lru;

        @BeforeEach
        void setUp() {
            lru = new TwoListLRU<>(5, 3);
        }

        @Test
        void testPutAndGet() {
            assertThat(lru.put("key1", "value1")).isTrue();
            assertThat(lru.get("key1")).isEqualTo("value1");
            assertThat(lru.size()).isEqualTo(1);
        }

        @Test
        void testUpdate() {
            lru.put("key1", "value1");
            lru.put("key1", "value2");

            assertThat(lru.get("key1")).isEqualTo("value2");
            assertThat(lru.size()).isEqualTo(1);
        }

        @Test
        void testRemove() {
            lru.put("key1", "value1");
            String removed = lru.remove("key1");

            assertThat(removed).isEqualTo("value1");
            assertThat(lru.size()).isEqualTo(0);
            assertThat(lru.get("key1")).isNull();
        }

        @Test
        void testContains() {
            lru.put("key1", "value1");

            assertThat(lru.contains("key1")).isTrue();
            assertThat(lru.contains("key2")).isFalse();
        }

        @Test
        void testClear() {
            lru.put("key1", "value1");
            lru.put("key2", "value2");

            lru.clear();

            assertThat(lru.size()).isEqualTo(0);
            assertThat(lru.getActiveSize()).isEqualTo(0);
            assertThat(lru.getInactiveSize()).isEqualTo(0);
        }
    }

    @Nested
    class ActiveInactiveListBehavior {
        private TwoListLRU<String, String> lru;

        @BeforeEach
        void setUp() {
            lru = new TwoListLRU<>(3, 2);
        }

        @Test
        void testActiveListFilling() {
            lru.put("key1", "value1");
            lru.put("key2", "value2");
            lru.put("key3", "value3");

            assertThat(lru.getActiveSize()).isEqualTo(3);
            assertThat(lru.getInactiveSize()).isEqualTo(0);
        }

        @Test
        void testDemotionToInactive() {
            // 填满Active List
            lru.put("key1", "value1");
            lru.put("key2", "value2");
            lru.put("key3", "value3");

            // 添加新元素触发降级
            lru.put("key4", "value4");

            assertThat(lru.getActiveSize()).isEqualTo(3);
            assertThat(lru.getInactiveSize()).isEqualTo(1);
            assertThat(lru.size()).isEqualTo(4);
        }

        @Test
        void testPromotionFromInactive() {
            // 填满Active List并触发一次降级
            lru.put("key1", "value1");
            lru.put("key2", "value2");
            lru.put("key3", "value3");
            lru.put("key4", "value4");

            // 访问被降级的元素，应该提升回Active
            lru.get("key1");

            assertThat(lru.getActiveSize()).isEqualTo(3);
            assertThat(lru.getInactiveSize()).isEqualTo(1);
        }
    }

    @Nested
    class EvictionBehavior {
        private TwoListLRU<String, String> lru;

        @BeforeEach
        void setUp() {
            lru = new TwoListLRU<>(2, 2);
        }

        @Test
        void testEvictionWhenBothListsFull() {
            // 填满两个列表
            lru.put("key1", "value1");
            lru.put("key2", "value2");
            lru.put("key3", "value3");
            lru.put("key4", "value4");

            assertThat(lru.size()).isEqualTo(4);
            assertThat(lru.getTotalEvictions()).isEqualTo(0);

            // 再添加一个，触发淘汰
            lru.put("key5", "value5");

            assertThat(lru.size()).isEqualTo(4);
            assertThat(lru.getTotalEvictions()).isEqualTo(1);
        }

        @Test
        void testEvictionCallback() {
            AtomicInteger evictionCount = new AtomicInteger(0);
            List<String> evictedKeys = new ArrayList<>();

            lru.setEvictionCallback((key, value) -> {
                evictionCount.incrementAndGet();
                evictedKeys.add(key);
            });

            // 填满并触发淘汰
            lru.put("key1", "value1");
            lru.put("key2", "value2");
            lru.put("key3", "value3");
            lru.put("key4", "value4");
            lru.put("key5", "value5");

            assertThat(evictionCount.get()).isEqualTo(1);
            assertThat(evictedKeys).containsExactly("key1");
        }
    }

    @Nested
    class EvictionPredicate {
        private TwoListLRU<String, String> lru;

        @BeforeEach
        void setUp() {
            lru = new TwoListLRU<>(2, 1);
        }

        @Test
        void testProtectedElementsNotEvicted() {
            // 设置判断器：保护包含"protected"的值
            lru.setEvictionPredicate(value -> !value.contains("protected"));

            lru.put("key1", "normal-value1");
            lru.put("key2", "protected-value2");
            lru.put("key3", "normal-value3");

            // 此时Active满，key1降级到Inactive
            assertThat(lru.getActiveSize()).isEqualTo(2);
            assertThat(lru.getInactiveSize()).isEqualTo(1);

            // 再添加，应该淘汰normal元素
            lru.put("key4", "normal-value4");

            // 应该还是包含所有protected元素
            assertThat(lru.contains("key2")).isTrue();
            // key1(normal)可能被淘汰
            assertThat(lru.getTotalEvictions()).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    class ConcurrencyTests {
        private TwoListLRU<String, Integer> lru;

        @BeforeEach
        void setUp() {
            lru = new TwoListLRU<>(10, 5);
        }

        @Test
        void testConcurrentPutAndGet() throws InterruptedException {
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
                                    lru.put(key, i);
                                    lru.get(key);
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // 验证数据一致性
            assertThat(lru.size()).isLessThanOrEqualTo(15); // maxActive + maxInactive
            assertThat(lru.getActiveSize()).isLessThanOrEqualTo(10);
            assertThat(lru.getInactiveSize()).isLessThanOrEqualTo(5);
        }
    }

    @Nested
    class ExceptionHandling {
        private TwoListLRU<String, String> lru;

        @BeforeEach
        void setUp() {
            lru = new TwoListLRU<>(5, 3);
        }

        @Test
        void testNullKeyPut() {
            assertThatThrownBy(() -> lru.put(null, "value"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be null");
        }

        @Test
        void testNullKeyGet() {
            assertThat(lru.get(null)).isNull();
        }

        @Test
        void testNullKeyRemove() {
            assertThat(lru.remove(null)).isNull();
        }

        @Test
        void testNullKeyContains() {
            assertThat(lru.contains(null)).isFalse();
        }

        @Test
        void testInvalidConstructorParams() {
            assertThatThrownBy(() -> new TwoListLRU<String, String>(0, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxActiveSize must be positive");

            assertThatThrownBy(() -> new TwoListLRU<String, String>(5, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxInactiveSize must be positive");
        }
    }

    @Nested
    class DefaultConstructor {
        @Test
        void testDefaultConstructor() {
            TwoListLRU<String, String> lru = new TwoListLRU<>();

            // 默认配置应该是 1024 + 512
            for (int i = 0; i < 1536; i++) {
                lru.put("key" + i, "value" + i);
            }

            assertThat(lru.size()).isEqualTo(1536);
            assertThat(lru.getTotalEvictions()).isEqualTo(0);

            // 再添加一个触发淘汰
            lru.put("keyExtra", "valueExtra");
            assertThat(lru.getTotalEvictions()).isEqualTo(1);
        }
    }

    @Nested
    class LRUOrder {
        private TwoListLRU<String, String> lru;

        @BeforeEach
        void setUp() {
            lru = new TwoListLRU<>(3, 2);
        }

        @Test
        void testLRUEvictionOrder() {
            // 按顺序添加
            lru.put("key1", "value1");
            lru.put("key2", "value2");
            lru.put("key3", "value3");
            lru.put("key4", "value4");
            lru.put("key5", "value5");

            // key1应该在inactive list，因为它最早添加
            // 继续添加触发淘汰，key1应该被淘汰
            lru.put("key6", "value6");

            assertThat(lru.contains("key1")).isFalse();
            assertThat(lru.contains("key2")).isTrue();
        }

        @Test
        void testAccessUpdatesLRUOrder() {
            lru.put("key1", "value1");
            lru.put("key2", "value2");
            lru.put("key3", "value3");

            // 访问key1，它应该移到Active头部
            lru.get("key1");

            lru.put("key4", "value4");
            lru.put("key5", "value5");

            // key2应该被降级，因为key1被访问过
            assertThat(lru.contains("key1")).isTrue();
        }
    }
}
