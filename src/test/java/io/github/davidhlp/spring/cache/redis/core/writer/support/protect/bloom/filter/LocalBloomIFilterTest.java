package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.filter;

import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.BloomFilterConfig;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.strategy.BloomHashStrategy;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.strategy.MessageDigestBloomHashStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LocalBloomIFilter 单元测试
 */
@DisplayName("LocalBloomIFilter Tests")
class LocalBloomIFilterTest {

    private BloomFilterConfig config;
    private BloomHashStrategy hashStrategy;
    private LocalBloomIFilter filter;

    @BeforeEach
    void setUp() {
        config = new BloomFilterConfig("test:", 1024, 3, 100);
        hashStrategy = new MessageDigestBloomHashStrategy();
        filter = new LocalBloomIFilter(config, hashStrategy);
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
        @DisplayName("returns false for different key in same cache")
        void mightContain_differentKey_returnsFalse() {
            filter.add("cache", "key1");
            assertThat(filter.mightContain("cache", "key2")).isFalse();
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

            // Add many keys to increase chance of false positive
            for (int i = 0; i < 50; i++) {
                filter.add(cacheName, "key" + i);
            }

            // This key was never added but might be reported as "contained"
            // due to false positive - this is expected behavior
            // We can't deterministically test false positives, but we can verify
            // that a definitely-unrelated key pattern returns false
            assertThat(filter.mightContain(cacheName, "definitely-not-added-key-xyz")).isFalse();
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
        void concurrentOperations_safe() throws InterruptedException {
            String cacheName = "concurrent-cache";
            int threadCount = 10;
            int operationsPerThread = 100;

            Thread[] threads = new Thread[threadCount];

            for (int t = 0; t < threadCount; t++) {
                final int threadNum = t;
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < operationsPerThread; i++) {
                        String key = "thread" + threadNum + "-key" + i;
                        filter.add(cacheName, key);
                        filter.mightContain(cacheName, key);
                    }
                });
            }

            for (Thread thread : threads) {
                thread.start();
            }

            for (Thread thread : threads) {
                thread.join();
            }

            // All keys should be findable
            for (int t = 0; t < threadCount; t++) {
                for (int i = 0; i < operationsPerThread; i++) {
                    String key = "thread" + t + "-key" + i;
                    assertThat(filter.mightContain(cacheName, key)).isTrue();
                }
            }
        }
    }
}
