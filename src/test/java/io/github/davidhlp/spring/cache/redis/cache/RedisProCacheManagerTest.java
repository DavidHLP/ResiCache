package io.github.davidhlp.spring.cache.redis.cache;





import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Collections;
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
        cacheManager = new RedisProCacheManager(
                cacheWriter,
                defaultConfiguration,
                ResiCacheFeatures.builder()
                        .meterRegistry(meterRegistry)
                        .build(),   // bloom/operationResolver/sync disabled
                Collections.emptyMap(),
                false);  // transactionAware
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

            // 验证传入的 customConfig 确实被采用(TTL=120s),而非回退默认(60s)
            assertThat(cache).isNotNull();
            assertThat(cache.getCacheConfiguration().getTtlFunction().getTimeToLive(null, null)).isEqualTo(Duration.ofSeconds(120));
        }

        @Test
        @DisplayName("uses default configuration when null provided")
        void createRedisCache_nullConfig_usesDefaultConfig() {
            String cacheName = "default-cache";

            RedisProCache cache = (RedisProCache) cacheManager.createRedisCache(cacheName, null);

            // 验证传 null 时回退到默认配置(TTL=60s,即 defaultConfiguration)
            assertThat(cache).isNotNull();
            assertThat(cache.getCacheConfiguration().getTtlFunction().getTimeToLive(null, null)).isEqualTo(Duration.ofSeconds(60));
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

            // null → 回退默认配置(TTL=60s)
            assertThat(result).isNotNull();
            assertThat(result.getCacheConfiguration().getTtlFunction().getTimeToLive(null, null)).isEqualTo(Duration.ofSeconds(60));
        }

        @Test
        @DisplayName("uses non-null configuration")
        void resolveCacheConfiguration_nonNull_usesProvided() {
            RedisCacheConfiguration customConfig = RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofSeconds(300));

            var result = cacheManager.createRedisCache("test", customConfig);

            // 非 null → 采用传入配置(TTL=300s)
            assertThat(result).isNotNull();
            assertThat(result.getCacheConfiguration().getTtlFunction().getTimeToLive(null, null)).isEqualTo(Duration.ofSeconds(300));
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

    @Nested
    @DisplayName("instantiateRedisProCache contract tests")
    class InstantiateRedisProCacheContract {

        @Test
        @DisplayName("createRedisCache 与 getMissingCache 走同一 instantiate seam — name 透传")
        void createAndMissing_useSameInstantiateSeam_namePropagated() {
            String name1 = "seam-cache-create";
            String name2 = "seam-cache-missing";

            RedisProCache fromCreate = (RedisProCache) cacheManager.createRedisCache(name1, null);
            // getMissingCache 是 protected, 走 createRedisCache(name, null) 等价路径
            // 这里我们通过 getCache 触发 missing 路径(会调 createRedisCache)
            var fromMissing = cacheManager.getCache(name2);

            assertThat(fromCreate.getName()).isEqualTo(name1);
            assertThat(fromMissing).isNotNull();
            assertThat(fromMissing.getName()).isEqualTo(name2);
        }

        @Test
        @DisplayName("createRedisCache(name, cfg) — cfg 非 null 时透传(不走默认)")
        void createRedisCache_nonNullConfig_propagatesConfig() {
            RedisCacheConfiguration customConfig = RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofSeconds(777));

            RedisProCache cache = (RedisProCache) cacheManager.createRedisCache("cfg-cache", customConfig);

            // cfg=777s 透传到 RedisProCache(证明 instantiateRedisProCache 未误调 resolve)
            assertThat(cache.getCacheConfiguration().getTtlFunction().getTimeToLive(null, null))
                    .isEqualTo(Duration.ofSeconds(777));
        }

        @Test
        @DisplayName("createRedisCache(name, null) — null cfg 走默认(证明 resolve 仍然生效)")
        void createRedisCache_nullConfig_fallsBackToDefault() {
            // defaultConfig TTL=60s (来自 @BeforeEach setUp)
            RedisProCache cache = (RedisProCache) cacheManager.createRedisCache("null-cfg-cache", null);

            assertThat(cache.getCacheConfiguration().getTtlFunction().getTimeToLive(null, null))
                    .isEqualTo(Duration.ofSeconds(60));
        }
    }
}
