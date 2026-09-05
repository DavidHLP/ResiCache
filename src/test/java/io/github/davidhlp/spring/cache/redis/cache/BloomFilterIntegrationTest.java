package io.github.davidhlp.spring.cache.redis.cache;






import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Import(TestRedisConfiguration.class)
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

    /**
     * 断言 Redis 中不存在任何 rebuilding marker 键(无 marker I/O 架构约束)。
     *
     * <p>ADR-01 删除 rebuilding marker/window 后,任何 Bloom 操作都不得读写
     * {@code *rebuild*} marker 键空间。flushDb 后该 keyspace 必须始终为空。
     */
    private void assertNoRebuildMarkerKeys() {
        assertThat(redisTemplate.keys("*rebuild*")).isEmpty();
    }

    private void addAndFlush(String cacheName, String key) {
        bloomFilter.add(cacheName, key);
        assertThat(bloomFilter.mightContain(cacheName, key)).isTrue();
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

    @Nested
    @DisplayName("Marker-free Bloom semantics (ADR-01)")
    class MarkerFreeSemanticsTests {

        @Test
        @DisplayName("add + mightContain never touch a rebuilding marker keyspace")
        void addAndCheck_neverTouchesMarkerKeyspace() {
            addAndFlush("marker-free-cache", "key:1");

            // 纯 add/check 往返不得产生任何 marker 键
            assertNoRebuildMarkerKeys();
        }

        @Test
        @DisplayName("explicit filter clear removes the key without leaving a marker")
        void clear_removesKeyWithoutMarker() {
            addAndFlush("marker-free-cache", "key:1");

            bloomFilter.clear("marker-free-cache");

            assertThat(bloomFilter.mightContain("marker-free-cache", "key:1")).isFalse();
            assertNoRebuildMarkerKeys();
        }

        @Test
        @DisplayName("cache CLEAN does not clear Bloom: old positive bits keep the GET path open")
        void cacheCleanDoesNotClearBloom_oldBitsSurvive() {
            addAndFlush("clean-keeps-bloom", "key:1");

            // 模拟普通缓存 CLEAN:只清缓存数据(bloom key 前缀之外),不清 Bloom 位
            redisTemplate.delete("clean-keeps-bloom::key:1");

            // 旧正位保留 → 仍是可能存在 → 走 Redis + loader,而不是被短路成静默 null
            assertThat(bloomFilter.mightContain("clean-keeps-bloom", "key:1")).isTrue();
            assertNoRebuildMarkerKeys();
        }

        @Test
        @DisplayName("re-add after clear restores the positive bit with no marker state")
        void reAddAfterClear_restoresBitWithoutMarker() {
            addAndFlush("marker-free-cache", "key:1");

            bloomFilter.clear("marker-free-cache");
            bloomFilter.add("marker-free-cache", "key:1");

            assertThat(bloomFilter.mightContain("marker-free-cache", "key:1")).isTrue();
            assertNoRebuildMarkerKeys();
        }
    }
}
