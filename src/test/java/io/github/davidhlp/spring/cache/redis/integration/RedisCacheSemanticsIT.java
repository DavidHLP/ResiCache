package io.github.davidhlp.spring.cache.redis.integration;

import io.github.davidhlp.spring.cache.redis.AbstractRedisIntegrationTest;
import io.github.davidhlp.spring.cache.redis.TestApplication;
import io.github.davidhlp.spring.cache.redis.TestRedisConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Import(TestRedisConfiguration.class)
@DisplayName("Redis Cache Semantics Integration Tests")
class RedisCacheSemanticsIT extends AbstractRedisIntegrationTest {

    @Autowired
    private TestCacheService cacheService;

    @Autowired
    private RedisTemplate<String, Object> redisCacheTemplate;

    private ValueOperations<String, Object> valueOps;

    @BeforeEach
    void setUp() {
        valueOps = redisCacheTemplate.opsForValue();
        redisCacheTemplate.getConnectionFactory().getConnection().flushDb();
        cacheService.reset();
    }

    @Nested
    @DisplayName("@RedisCacheable semantics")
    class CacheableSemanticsTests {

        @Test
        @DisplayName("should cache method result")
        void cacheable_cachesResult() {
            String result1 = cacheService.getById(1L);
            assertThat(result1).isEqualTo("value-1");
            assertThat(cacheService.getCallCount()).isEqualTo(1);

            String result2 = cacheService.getById(1L);
            assertThat(result2).isEqualTo("value-1");
            assertThat(cacheService.getCallCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should use different keys for different arguments")
        void cacheable_differentArgs_differentKeys() {
            cacheService.getById(1L);
            cacheService.getById(2L);
            assertThat(cacheService.getCallCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("@RedisCachePut semantics")
    class CachePutSemanticsTests {

        @Test
        @DisplayName("should always execute method and update cache")
        void cachePut_alwaysExecutesAndUpdates() {
            cacheService.putById(1L, "first");
            assertThat(cacheService.getCallCount()).isEqualTo(1);

            cacheService.putById(1L, "second");
            assertThat(cacheService.getCallCount()).isEqualTo(2);

            // The cache should have the latest value
            String cached = (String) valueOps.get("testCache::1");
            assertThat(cached).contains("second");
        }
    }

    @Nested
    @DisplayName("@RedisCacheEvict semantics")
    class CacheEvictSemanticsTests {

        @Test
        @DisplayName("should evict specific key")
        void cacheEvict_removesKey() {
            cacheService.getById(1L);
            assertThat(valueOps.get("testCache::1")).isNotNull();

            cacheService.evictById(1L);
            assertThat(valueOps.get("testCache::1")).isNull();
        }

        @Test
        @DisplayName("should evict all entries when allEntries=true")
        void cacheEvict_allEntries_removesAll() {
            cacheService.getById(1L);
            cacheService.getById(2L);
            assertThat(valueOps.get("testCache::1")).isNotNull();
            assertThat(valueOps.get("testCache::2")).isNotNull();

            cacheService.evictAll();
            assertThat(valueOps.get("testCache::1")).isNull();
            assertThat(valueOps.get("testCache::2")).isNull();
        }
    }

    @Nested
    @DisplayName("condition and unless semantics")
    class ConditionUnlessTests {

        @Test
        @DisplayName("condition=false should skip cache")
        void conditionFalse_skipsCache() {
            cacheService.getByIdWithCondition(-1L);
            assertThat(cacheService.getCallCount()).isEqualTo(1);

            cacheService.getByIdWithCondition(-1L);
            assertThat(cacheService.getCallCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("condition=true should use cache")
        void conditionTrue_usesCache() {
            cacheService.getByIdWithCondition(1L);
            assertThat(cacheService.getCallCount()).isEqualTo(1);

            cacheService.getByIdWithCondition(1L);
            assertThat(cacheService.getCallCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("unless=true should not cache result")
        void unlessTrue_doesNotCache() {
            cacheService.getByIdWithUnless(-1L); // returns null, unless evaluates to true
            assertThat(cacheService.getCallCount()).isEqualTo(1);

            cacheService.getByIdWithUnless(-1L);
            // Method should be called again because result was not cached
            assertThat(cacheService.getCallCount()).isEqualTo(2);
        }
    }
}
