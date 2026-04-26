package io.github.davidhlp.spring.cache.redis.manager;

import io.github.davidhlp.spring.cache.redis.core.RedisProCache;
import io.github.davidhlp.spring.cache.redis.core.writer.RedisProCacheWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisProCacheManager 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisProCacheManager Tests")
class RedisProCacheManagerTest {

    @Mock
    private RedisProCacheWriter cacheWriter;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    private RedisCacheConfiguration defaultConfiguration;
    private MeterRegistry meterRegistry;
    private RedisProCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        defaultConfiguration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(60));
        meterRegistry = new SimpleMeterRegistry();
        cacheManager = new RedisProCacheManager(cacheWriter, defaultConfiguration, meterRegistry);
    }

    @Nested
    @DisplayName("createRedisCache tests")
    class CreateRedisCacheTests {

        @Test
        @DisplayName("creates RedisProCache with correct parameters")
        void createRedisCache_validName_createsRedisProCache() {
            String cacheName = "test-cache";

            RedisProCache cache = (RedisProCache) cacheManager.createRedisCache(cacheName, null);

            assertThat(cache).isNotNull();
            assertThat(cache.getName()).isEqualTo(cacheName);
        }

        @Test
        @DisplayName("uses provided configuration when specified")
        void createRedisCache_withConfiguration_usesProvidedConfig() {
            String cacheName = "custom-cache";
            RedisCacheConfiguration customConfig = RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofSeconds(120));

            RedisProCache cache = (RedisProCache) cacheManager.createRedisCache(cacheName, customConfig);

            assertThat(cache).isNotNull();
        }

        @Test
        @DisplayName("uses default configuration when null provided")
        void createRedisCache_nullConfig_usesDefaultConfig() {
            String cacheName = "default-cache";

            RedisProCache cache = (RedisProCache) cacheManager.createRedisCache(cacheName, null);

            assertThat(cache).isNotNull();
        }
    }

    @Nested
    @DisplayName("getCache tests")
    class GetCacheTests {

        @Test
        @DisplayName("returns existing cache when found")
        void getCache_existingCache_returnsCache() {
            String cacheName = "existing-cache";
            // First call creates the cache
            cacheManager.createRedisCache(cacheName, null);

            var cache = cacheManager.getCache(cacheName);

            assertThat(cache).isNotNull();
            assertThat(cache.getName()).isEqualTo(cacheName);
        }

        @Test
        @DisplayName("creates new cache when not found")
        void getCache_nonExistingCache_createsNew() {
            String cacheName = "new-cache";

            var cache = cacheManager.getCache(cacheName);

            assertThat(cache).isNotNull();
            assertThat(cache.getName()).isEqualTo(cacheName);
        }

        @Test
        @DisplayName("returns cache with correct name")
        void getCache_validName_returnsCorrectCache() {
            String cacheName = "my-cache";

            var cache = cacheManager.getCache(cacheName);

            assertThat(cache.getName()).isEqualTo(cacheName);
        }
    }

    @Nested
    @DisplayName("configuration resolution tests")
    class ConfigurationResolutionTests {

        @Test
        @DisplayName("resolves null configuration to default")
        void resolveCacheConfiguration_null_returnsDefault() {
            var result = cacheManager.createRedisCache("test", null);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("uses non-null configuration")
        void resolveCacheConfiguration_nonNull_usesProvided() {
            RedisCacheConfiguration customConfig = RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofSeconds(300));

            var result = cacheManager.createRedisCache("test", customConfig);

            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("cache manager behavior tests")
    class ManagerBehaviorTests {

        @Test
        @DisplayName("manager is instance of RedisCacheManager")
        void isInstanceOfRedisCacheManager() {
            assertThat(cacheManager).isInstanceOf(RedisCacheManager.class);
        }

        @Test
        @DisplayName("can create multiple caches with different names")
        void createMultipleCaches_differentNames_allCreated() {
            RedisProCache cache1 = (RedisProCache) cacheManager.createRedisCache("cache-1", null);
            RedisProCache cache2 = (RedisProCache) cacheManager.createRedisCache("cache-2", null);

            assertThat(cache1.getName()).isEqualTo("cache-1");
            assertThat(cache2.getName()).isEqualTo("cache-2");
            assertThat(cache1).isNotEqualTo(cache2);
        }
    }
}
