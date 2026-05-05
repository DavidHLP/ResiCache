package io.github.davidhlp.spring.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.BloomFilterConfig;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.filter.RedisBloomIFilter;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.strategy.BloomHashStrategy;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.strategy.MessageDigestBloomHashStrategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.HashSet;
import java.util.Set;

@SpringBootTest
@ActiveProfiles("integration-test")
@ContextConfiguration(classes = TestRedisConfiguration.class)
@DisplayName("Bloom Filter Integration Tests")
class BloomFilterIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private BloomFilterConfig config;
    private BloomHashStrategy hashStrategy;
    private RedisBloomIFilter bloomFilter;

    @BeforeEach
    void setUp() {
        config = new BloomFilterConfig("bf:", 1024, 3, 100);
        hashStrategy = new MessageDigestBloomHashStrategy();
        bloomFilter = new RedisBloomIFilter(redisTemplate, config, hashStrategy, null);
        bloomFilter.init();

        redisTemplate.getConnectionFactory().getConnection().flushDb();
    }

    @Nested
    @DisplayName("Add and Check Operations")
    class AddAndCheckTests {

        @Test
        @DisplayName("should add key and confirm it might contain")
        void addAndCheckKey() {
            String cacheName = "test-cache";
            String key = "user:123";

            bloomFilter.add(cacheName, key);
            boolean mightContain = bloomFilter.mightContain(cacheName, key);

            assertThat(mightContain).isTrue();
        }

        @Test
        @DisplayName("should return false for key that was never added")
        void checkNonExistentKey() {
            String cacheName = "test-cache";
            String key = "user:999";

            boolean mightContain = bloomFilter.mightContain(cacheName, key);

            assertThat(mightContain).isFalse();
        }

        @Test
        @DisplayName("should handle multiple keys in same cache")
        void multipleKeysInSameCache() {
            String cacheName = "users";

            bloomFilter.add(cacheName, "user:1");
            bloomFilter.add(cacheName, "user:2");
            bloomFilter.add(cacheName, "user:3");

            assertThat(bloomFilter.mightContain(cacheName, "user:1")).isTrue();
            assertThat(bloomFilter.mightContain(cacheName, "user:2")).isTrue();
            assertThat(bloomFilter.mightContain(cacheName, "user:3")).isTrue();
            assertThat(bloomFilter.mightContain(cacheName, "user:4")).isFalse();
        }

        @Test
        @DisplayName("should handle same key in different caches independently")
        void sameKeyDifferentCaches() {
            String key = "item:1";

            bloomFilter.add("products", key);

            assertThat(bloomFilter.mightContain("products", key)).isTrue();
            assertThat(bloomFilter.mightContain("orders", key)).isFalse();
        }
    }

    @Nested
    @DisplayName("Clear Operations")
    class ClearOperationsTests {

        @Test
        @DisplayName("should clear all entries for a cache")
        void clearCacheEntries() {
            String cacheName = "clear-test";

            bloomFilter.add(cacheName, "key:1");
            bloomFilter.add(cacheName, "key:2");

            assertThat(bloomFilter.mightContain(cacheName, "key:1")).isTrue();

            bloomFilter.clear(cacheName);

            assertThat(bloomFilter.mightContain(cacheName, "key:1")).isFalse();
            assertThat(bloomFilter.mightContain(cacheName, "key:2")).isFalse();
        }

        @Test
        @DisplayName("should not affect other caches when clearing one")
        void clearOneCacheDoesNotAffectOthers() {
            bloomFilter.add("cache-a", "key:1");
            bloomFilter.add("cache-b", "key:1");

            bloomFilter.clear("cache-a");

            assertThat(bloomFilter.mightContain("cache-a", "key:1")).isFalse();
            assertThat(bloomFilter.mightContain("cache-b", "key:1")).isTrue();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("should handle null cacheName gracefully on add")
        void nullCacheNameAdd() {
            bloomFilter.add(null, "key");
            assertThat(bloomFilter.mightContain(null, "key")).isFalse();
        }

        @Test
        @DisplayName("should handle null key gracefully on add")
        void nullKeyAdd() {
            bloomFilter.add("cache", null);
            assertThat(bloomFilter.mightContain("cache", null)).isFalse();
        }

        @Test
        @DisplayName("should handle empty string key")
        void emptyStringKey() {
            bloomFilter.add("cache", "");
            boolean result = bloomFilter.mightContain("cache", "");
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("False Positive Rate")
    class FalsePositiveRateTests {

        @Test
        @DisplayName("should have acceptable false positive rate")
        void falsePositiveRateWithinBounds() {
            String cacheName = "fp-test";
            int itemCount = 100;
            Set<String> addedKeys = new HashSet<>();

            for (int i = 0; i < itemCount; i++) {
                String key = "user:" + i;
                bloomFilter.add(cacheName, key);
                addedKeys.add(key);
            }

            int falsePositives = 0;
            int checkCount = 500;
            for (int i = itemCount; i < itemCount + checkCount; i++) {
                String key = "user:" + i;
                if (bloomFilter.mightContain(cacheName, key)) {
                    falsePositives++;
                }
            }

            double falsePositiveRate = (double) falsePositives / checkCount;
            assertThat(falsePositiveRate).isLessThan(0.15);

            for (String key : addedKeys) {
                assertThat(bloomFilter.mightContain(cacheName, key)).isTrue();
            }
        }
    }
}
