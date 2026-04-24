package io.github.davidhlp.spring.cache.redis.strategy.eviction.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TwoListLRU concurrency tests verifying thread-safety under concurrent access.
 */
@DisplayName("TwoListLRU Concurrent Tests")
class TwoListLRUConcurrentTest {

    private static final int THREAD_COUNT = 8;
    private static final int OPERATIONS_PER_THREAD = 100;

    @Test
    @DisplayName("concurrentPutAndGet_maintainsDataIntegrity")
    void concurrentPutAndGet_maintainsDataIntegrity() throws InterruptedException {
        TwoListLRU<String, String> lru = new TwoListLRU<>(50, 25);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        ConcurrentHashMap<String, AtomicInteger> valueCounts = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, String> storedValues = new ConcurrentHashMap<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        String key = "key-" + threadId + "-" + j;
                        String value = "value-" + threadId + "-" + j;
                        storedValues.put(key, value);
                        lru.put(key, value);

                        String retrieved = lru.get(key);
                        if (retrieved != null) {
                            valueCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        org.awaitility.Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .until(() -> doneLatch.getCount() == 0);

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // Verify data integrity - all stored values should be retrievable
        int matched = 0;
        for (var entry : storedValues.entrySet()) {
            String retrieved = lru.get(entry.getKey());
            if (entry.getValue().equals(retrieved)) {
                matched++;
            }
        }
        assertThat(matched).isEqualTo(storedValues.size());
    }

    @Test
    @DisplayName("concurrentPutAcrossThreads_noDataCorruption")
    void concurrentPutAcrossThreads_noDataCorruption() throws InterruptedException {
        TwoListLRU<String, Integer> lru = new TwoListLRU<>(100, 50);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        ConcurrentHashMap<String, Integer> threadOperations = new ConcurrentHashMap<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        String key = "thread-" + threadId + "-key-" + j;
                        Integer value = threadId * 1000 + j;
                        threadOperations.put(key, value);
                        lru.put(key, value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        org.awaitility.Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .until(() -> doneLatch.getCount() == 0);

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // Verify no corruption - all values should match what was stored
        int correctValues = 0;
        for (var entry : threadOperations.entrySet()) {
            Integer retrieved = lru.get(entry.getKey());
            if (entry.getValue().equals(retrieved)) {
                correctValues++;
            }
        }
        assertThat(correctValues).isEqualTo(threadOperations.size());
        // Verify size is consistent
        assertThat(lru.size()).isLessThanOrEqualTo(150); // maxActive + maxInactive
    }

    @Test
    @DisplayName("concurrentEviction_noDeadlock")
    void concurrentEviction_noDeadlock() throws InterruptedException {
        TwoListLRU<String, String> lru = new TwoListLRU<>(20, 10);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        String key = "thread-" + threadId + "-entry-" + j;
                        String value = "value-for-" + key;
                        try {
                            lru.put(key, value);
                            // Occasionally get to trigger promotion
                            if (j % 3 == 0) {
                                lru.get(key);
                            }
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        org.awaitility.Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .until(() -> doneLatch.getCount() == 0);

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(errorCount.get()).isZero();
        // System should be in a consistent state
        assertThat(lru.size()).isLessThanOrEqualTo(30);
    }
}
