package io.github.davidhlp.spring.cache.redis.cache;





import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * LocalBloomIFilter 单元测试
 */
@DisplayName("LocalBloomIFilter Tests")
class LocalBloomIFilterTest {

    private BloomFilterConfig config;
    private LocalBloomIFilter filter;

    @BeforeEach
    void setUp() {
        config = new BloomFilterConfig("test:", 1024, 3, 100);
        filter = new LocalBloomIFilter(config);
    }

    @Nested
    @DisplayName("add")
    class AddTests {

        @Test
        @DisplayName("adds key to bloom filter")
        void add_validKey_addsToFilter() {
            String cacheName = "test-cache";
            String key = "test-key";

            filter.add(cacheName, key);

            assertThat(filter.mightContain(cacheName, key)).isTrue();
        }

        @Test
        @DisplayName("handles null cacheName gracefully")
        void add_nullCacheName_doesNotThrow() {
            filter.add(null, "key");
            // Should not throw
        }

        @Test
        @DisplayName("handles null key gracefully")
        void add_nullKey_doesNotThrow() {
            filter.add("cache", null);
            // Should not throw
        }

        @Test
        @DisplayName("adds multiple keys to same cache")
        void add_multipleKeys_allAdded() {
            String cacheName = "test-cache";

            filter.add(cacheName, "key1");
            filter.add(cacheName, "key2");
            filter.add(cacheName, "key3");

            assertThat(filter.mightContain(cacheName, "key1")).isTrue();
            assertThat(filter.mightContain(cacheName, "key2")).isTrue();
            assertThat(filter.mightContain(cacheName, "key3")).isTrue();
        }

        @Test
        @DisplayName("maintains separate filters for different caches")
        void add_differentCaches_separateFilters() {
            filter.add("cache1", "shared-key");
            filter.add("cache2", "shared-key");

            assertThat(filter.mightContain("cache1", "shared-key")).isTrue();
            assertThat(filter.mightContain("cache2", "shared-key")).isTrue();

            // key in cache1 should not affect cache2
            filter.clear("cache1");
            assertThat(filter.mightContain("cache1", "shared-key")).isFalse();
            assertThat(filter.mightContain("cache2", "shared-key")).isTrue();
        }
    }

    @Nested
    @DisplayName("mightContain")
    class MightContainTests {

        @Test
        @DisplayName("returns true for added key")
        void mightContain_addedKey_returnsTrue() {
            filter.add("cache", "key");
            assertThat(filter.mightContain("cache", "key")).isTrue();
        }

        @Test
        @DisplayName("returns false for key never added")
        void mightContain_neverAdded_returnsFalse() {
            assertThat(filter.mightContain("cache", "never-added")).isFalse();
        }


        @Test
        @DisplayName("returns false for key in different cache")
        void mightContain_differentCache_returnsFalse() {
            filter.add("cache1", "key");
            assertThat(filter.mightContain("cache2", "key")).isFalse();
        }

        @Test
        @DisplayName("returns false for null cacheName")
        void mightContain_nullCacheName_returnsFalse() {
            filter.add("cache", "key");
            assertThat(filter.mightContain(null, "key")).isFalse();
        }

        @Test
        @DisplayName("returns false for null key")
        void mightContain_nullKey_returnsFalse() {
            filter.add("cache", "key");
            assertThat(filter.mightContain("cache", null)).isFalse();
        }

        @Test
        @DisplayName("returns false for never-seen cache")
        void mightContain_unknownCache_returnsFalse() {
            assertThat(filter.mightContain("unknown-cache", "key")).isFalse();
        }
    }

    @Nested
    @DisplayName("clear")
    class ClearTests {

        @Test
        @DisplayName("clears filter for cache")
        void clear_existingCache_clearsFilter() {
            String cacheName = "test-cache";
            filter.add(cacheName, "key1");
            filter.add(cacheName, "key2");

            filter.clear(cacheName);

            assertThat(filter.mightContain(cacheName, "key1")).isFalse();
            assertThat(filter.mightContain(cacheName, "key2")).isFalse();
        }

        @Test
        @DisplayName("handles clearing unknown cache gracefully")
        void clear_unknownCache_doesNotThrow() {
            filter.clear("unknown-cache");
            // Should not throw
        }

        @Test
        @DisplayName("handles null cacheName gracefully")
        void clear_nullCacheName_doesNotThrow() {
            filter.clear(null);
            // Should not throw
        }

        @Test
        @DisplayName("clearing one cache does not affect other caches")
        void clear_oneCache_othersUnaffected() {
            filter.add("cache1", "key");
            filter.add("cache2", "key");

            filter.clear("cache1");

            assertThat(filter.mightContain("cache1", "key")).isFalse();
            assertThat(filter.mightContain("cache2", "key")).isTrue();
        }
    }

    @Nested
    @DisplayName("False Positive Scenario")
    class FalsePositiveTests {

        @Test
        @DisplayName("bloom filter may have false positives for unrelated keys")
        void mightContain_unrelatedKey_mayReturnFalsePositive() {
            String cacheName = "test-cache";

            filter = new LocalBloomIFilter(new BloomFilterConfig("test:", 1, 1, 100));
            assertThat(filter.mightContain(cacheName, "unadded")).isFalse();
            filter.add(cacheName, "added");
            assertThat(filter.mightContain(cacheName, "unadded")).isTrue();
            assertThat(filter.mightContain(cacheName, "added")).isTrue();
        }

        @Test
        @DisplayName("bloom filter returns definitive miss for never-accessed cache")
        void mightContain_neverAccessedCache_returnsFalse() {
            assertThat(filter.mightContain("brand-new-cache", "any-key")).isFalse();
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("concurrent add and mightContain operations do not cause exceptions")
        void concurrentOperations_safe() throws Exception {
            String cacheName = "concurrent-cache";
            int threadCount = 10;
            int operationsPerThread = 100;
            ExecutorService workers = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> results = new ArrayList<>();
            try {
                for (int t = 0; t < threadCount; t++) {
                    final int threadNum = t;
                    results.add(workers.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        for (int i = 0; i < operationsPerThread; i++) {
                            String key = "thread" + threadNum + "-key" + i;
                            filter.add(cacheName, key);
                            assertThat(filter.mightContain(cacheName, key)).isTrue();
                        }
                        return null;
                    }));
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                for (Future<?> result : results) {
                    result.get(10, TimeUnit.SECONDS);
                }
                for (int t = 0; t < threadCount; t++) {
                    for (int i = 0; i < operationsPerThread; i++) {
                        assertThat(filter.mightContain(cacheName, "thread" + t + "-key" + i)).isTrue();
                    }
                }
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
