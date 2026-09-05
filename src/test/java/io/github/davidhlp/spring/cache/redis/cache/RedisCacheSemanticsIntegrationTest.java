package io.github.davidhlp.spring.cache.redis.cache;





import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Import(TestRedisConfiguration.class)
@DisplayName("Redis Cache Semantics Integration Tests")
class RedisCacheSemanticsIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired
    private TestCacheService cacheService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private BloomSupport bloomSupport;

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

            // The cache should have the latest value. Read back through the cache
            // abstraction (getById) rather than valueOps directly: ResiCache stores values
            // wrapped in CachedValue (carrying ttl/createdTime for early-expiration), so a
            // raw valueOps.get yields a CachedValue envelope; getById unwraps it. The
            // callCount staying at 2 also confirms the get hit the put's write.
            String cached = cacheService.getById(1L);
            assertThat(cacheService.getCallCount()).isEqualTo(2);
            assertThat(cached).isEqualTo("second");
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

    @Nested
    @DisplayName("ADR-01 CLEAN/GET race 与多上下文(marker-free)")
    class CleanGetRaceTests {
        /**
         * 同一 key:CLEAN 并发于 GET —— loader 永不因空 Bloom 被短路。
         *
         * <p>精确 loader 调用次数不稳定(CLEAN 清缓存与 GET 回填的时序交错),
         * 断言只锁定安全侧不变量:每次 GET 都返回真实值(null 是旧缺陷的静默短路),
         * 且 loader 至少被调用一次(不被 Bloom 阻断)。
         */
        @Test
        @DisplayName("concurrent CLEAN/GET returns real values; loader is never short-circuited")
        void concurrentCleanGet_loaderNeverShortCircuited() throws Exception {
            // 预热:布隆有位 + 数据在缓存
            cacheService.getByIdWithBloomFilter(7L);
            assertThat(cacheService.getCallCount()).isEqualTo(1);

            int rounds = 8;
            ExecutorService pool = Executors.newFixedThreadPool(rounds);
            try {
                CountDownLatch start = new CountDownLatch(1);
                @SuppressWarnings("unchecked")
                Future<String>[] futures = new Future[rounds];
                for (int i = 0; i < rounds; i++) {
                    final boolean isCleaner = (i % 2 == 0);
                    futures[i] = pool.submit(() -> {
                        start.await();
                        if (isCleaner) {
                            cacheService.evictAll();
                            return CleanerResult.CLEANED.name();
                        }
                        return cacheService.getByIdWithBloomFilter(7L);
                    });
                }
                start.countDown();
                int cleanerCount = 0;
                for (int i = 0; i < futures.length; i++) {
                    String value = futures[i].get(30, TimeUnit.SECONDS);
                    if (i % 2 == 0) {
                        assertThat(value).isEqualTo(CleanerResult.CLEANED.name());
                        cleanerCount++;
                    } else {
                        assertThat(value)
                                .as("GET 绝不因空 Bloom 短路返回 null(旧缺陷)")
                                .startsWith("bloom-value-7");
                    }
                }
                assertThat(cleanerCount).isEqualTo(rounds / 2);
            } finally {
                pool.shutdownNow();
            }

            // 确定性收尾:race 后再 CLEAN 一次,紧接 GET 必须调 loader 返回真实值
            int beforeFinal = cacheService.getCallCount();
            cacheService.evictAll();
            String finalValue = cacheService.getByIdWithBloomFilter(7L);
            assertThat(finalValue).isEqualTo("bloom-value-7");
            assertThat(cacheService.getCallCount())
                    .as("CLEAN 后 GET 必须重入 loader(marker-free 契约);"
                            + "含此前已被并发 GET 重入的计数")
                    .isGreaterThan(beforeFinal);
        }

        /** 区分 CLEAN 任务与 GET 任务的返回哨兵。 */
        private enum CleanerResult {
            CLEANED
        }

        /**
         * 两个独立缓存上下文共享同一 Redis —— CLEAN 只影响自身上下文的数据层。
         *
         * <p>上下文 A 预热 key 并经 {@link BloomSupport} 回填;上下文 B 对同 key 的 GET
         * 走真实 loader 路径。断言 B 的 loader 可达(CLEAN/Bloom 无跨上下文阻断),
         * 且无任何 marker 键。
         */
        @Test
        @DisplayName("independent cache contexts share Redis; bloom does not block across them")
        void independentContexts_shareRedisWithoutMarker() {
            org.springframework.cache.Cache cacheA = cacheManager.getCache("testCache");
            org.springframework.cache.Cache cacheB = cacheManager.getCache("testCache");
            assertThat(cacheA).isNotNull();
            assertThat(cacheB).isNotNull();

            // 上下文 A:直接写入数据层 + 布隆回填(模拟数据源已存在)
            cacheA.put("race-key", "from-A");
            bloomSupport.add("testCache", "race-key");
            assertThat(redisCacheTemplate.opsForValue().get("testCache::race-key")).isNotNull();

            // 上下文 B:同一 Redis 上读取 — 命中 A 写入的数据
            org.springframework.cache.Cache.ValueWrapper hit =
                    cacheB.get("race-key");
            assertThat(hit).isNotNull();
            assertThat(hit.get()).isEqualTo("from-A");

            // 上下文 B 的 CLEAN 只清数据层,不触碰布隆、不产生 marker
            cacheB.clear();
            assertThat(redisCacheTemplate.opsForValue().get("testCache::race-key")).isNull();
            assertThat(bloomSupport.mightContain("testCache", "race-key")).isTrue();
            assertThat(redisCacheTemplate.keys("*rebuild*")).isEmpty();
        }
    }
}
