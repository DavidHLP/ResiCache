package io.github.davidhlp.spring.cache.redis.cache;




import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RedisBloomIFilter 集成测试 — 真实 Redis 验证。
 *
 * <p>布隆位图由 Redis hash 中的真实字段驱动。故障注入测试单独使用 mock，
 * 因为真实 Redis 无法按测试要求主动抛出异常。
 */
@DisplayName("RedisBloomIFilter Tests (real Redis)")
class RedisBloomIFilterIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private BloomFilterConfig config;
    private BloomHashStrategy hashStrategy;
    private RedisBloomIFilter filter;

    @BeforeEach
    void setUp() {
        // RedisBloomIFilter 的 pipeline 返回原始 hash 值；使用字符串反序列化验证真实位。
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        redisTemplate.setValueSerializer(stringSerializer);
        redisTemplate.setHashValueSerializer(stringSerializer);
        redisTemplate.getConnectionFactory().getConnection().flushDb();

        config = new BloomFilterConfig("bf:", 1024, 3, 100);
        hashStrategy = new MessageDigestBloomHashStrategy();
        filter = new RedisBloomIFilter(redisTemplate, config, hashStrategy, null);
        filter.init();
    }

    @Nested
    @DisplayName("add")
    class AddTests {

        @Test
        @DisplayName("adds key to real Redis bloom filter")
        void add_validKey_isVisibleToRealRedis() {
            filter.add("test-cache", "test-key");

            assertThat(filter.mightContain("test-cache", "test-key")).isTrue();
        }

        @Test
        @DisplayName("handles null cacheName gracefully")
        void add_nullCacheName_doesNotThrow() {
            filter.add(null, "key");

            assertThat(filter.mightContain(null, "key")).isFalse();
        }

        @Test
        @DisplayName("handles null key gracefully")
        void add_nullKey_doesNotThrow() {
            filter.add("cache", null);

            assertThat(filter.mightContain("cache", null)).isFalse();
        }

        @Test
        @DisplayName("real add is observable through mightContain")
        void add_success_isObservable() {
            filter.add("test-cache", "test-key");

            assertThat(filter.mightContain("test-cache", "test-key")).isTrue();
        }
    }

    @Nested
    @DisplayName("mightContain")
    class MightContainTests {

        @Test
        @DisplayName("returns true when all real hash positions exist")
        void mightContain_allPositionsExist_returnsTrue() {
            filter.add("test-cache", "test-key");

            assertThat(filter.mightContain("test-cache", "test-key")).isTrue();
        }

        @Test
        @DisplayName("returns false when a real hash position is missing")
        void mightContain_anyPositionMissing_returnsFalse() {
            filter.add("test-cache", "test-key");
            int missingPosition = hashStrategy.positionsFor("test-key", config)[1];
            redisTemplate.opsForHash().delete("bf:test-cache", String.valueOf(missingPosition));

            assertThat(filter.mightContain("test-cache", "test-key")).isFalse();
        }

        @Test
        @DisplayName("handles null cacheName gracefully")
        void mightContain_nullCacheName_returnsFalse() {
            assertThat(filter.mightContain(null, "key")).isFalse();
        }

        @Test
        @DisplayName("handles null key gracefully")
        void mightContain_nullKey_returnsFalse() {
            assertThat(filter.mightContain("cache", null)).isFalse();
        }

        @Test
        @DisplayName("returns true on Redis exception to avoid false negatives")
        void mightContain_exception_returnsTrue() {
            // fault injection — real Redis cannot throw on demand
            RedisTemplate<String, String> throwingTemplate = mock(RedisTemplate.class);
            when(throwingTemplate.executePipelined(any(RedisCallback.class)))
                    .thenThrow(new RuntimeException("Redis error"));
            RedisBloomIFilter faultFilter =
                    new RedisBloomIFilter(throwingTemplate, config, hashStrategy, null);
            faultFilter.init();

            assertThat(faultFilter.mightContain("test-cache", "test-key")).isTrue();
        }

        @Test
        @DisplayName("returns false when all real positions are absent")
        void mightContain_allPositionsNull_returnsFalse() {
            assertThat(redisTemplate.hasKey("bf:test-cache")).isFalse();

            assertThat(filter.mightContain("test-cache", "test-key")).isFalse();
        }
    }

    @Nested
    @DisplayName("clear")
    class ClearTests {

        @Test
        @DisplayName("deletes bloom filter key from real Redis")
        void clear_existingCache_deletesKey() {
            filter.add("test-cache", "test-key");
            assertThat(redisTemplate.hasKey("bf:test-cache")).isTrue();

            filter.clear("test-cache");

            assertThat(redisTemplate.hasKey("bf:test-cache")).isFalse();
            assertThat(filter.mightContain("test-cache", "test-key")).isFalse();
        }

        @Test
        @DisplayName("handles null cacheName gracefully")
        void clear_nullCacheName_doesNotThrow() {
            filter.clear(null);

            assertThat(redisTemplate.hasKey("bf:null")).isFalse();
        }

        @Test
        @DisplayName("handles Redis delete exception gracefully")
        void clear_exception_doesNotThrow() {
            // fault injection — real Redis cannot throw on demand
            RedisTemplate<String, String> throwingTemplate = mock(RedisTemplate.class);
            when(throwingTemplate.delete(anyString()))
                    .thenThrow(new RuntimeException("Redis error"));
            RedisBloomIFilter faultFilter =
                    new RedisBloomIFilter(throwingTemplate, config, hashStrategy, null);
            faultFilter.init();

            faultFilter.clear("test-cache");
        }
    }

    @Nested
    @DisplayName("False Positive Scenario")
    class FalsePositiveTests {

        @Test
        @DisplayName("real Redis bloom filter stays within the false-positive bound")
        void mightContain_manyAddedKeys_staysWithinFalsePositiveRate() {
            String cacheName = "test-cache";
            int itemCount = 100;
            int checkCount = 500;

            for (int i = 0; i < itemCount; i++) {
                filter.add(cacheName, "key-" + i);
            }

            int falsePositives = 0;
            for (int i = itemCount; i < itemCount + checkCount; i++) {
                if (filter.mightContain(cacheName, "key-" + i)) {
                    falsePositives++;
                }
            }

            double falsePositiveRate = (double) falsePositives / checkCount;
            assertThat(falsePositiveRate).isLessThan(0.15);
            assertThat(filter.mightContain(cacheName, "key-0")).isTrue();
        }
    }

    @Nested
    @DisplayName("False Negative Prevention")
    class FalseNegativePreventionTests {

        @Test
        @DisplayName("returns true on Redis error to prevent false negatives")
        void mightContain_redisError_returnsTrue() {
            // fault injection — real Redis cannot throw on demand
            RedisTemplate<String, String> throwingTemplate = mock(RedisTemplate.class);
            when(throwingTemplate.executePipelined(any(RedisCallback.class)))
                    .thenThrow(new RuntimeException("Connection failed"));
            RedisBloomIFilter faultFilter =
                    new RedisBloomIFilter(throwingTemplate, config, hashStrategy, null);
            faultFilter.init();

            assertThat(faultFilter.mightContain("test-cache", "test-key")).isTrue();
        }
    }
}
