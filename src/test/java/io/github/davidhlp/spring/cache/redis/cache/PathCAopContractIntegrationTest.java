package io.github.davidhlp.spring.cache.redis.cache;





import java.util.concurrent.TimeUnit;
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

/**
 * AOP 行为回归契约测试.
 *
 * <p>固化当前 AOP 行为契约:
 * <ul>
 *   <li>纯 Spring {@code @Cacheable} 通过 ResiCache 缓存链路正常工作</li>
 *   <li>{@code @RedisCacheable} + {@code useBloomFilter} 触发布隆处理器</li>
 *   <li>{@code @RedisCacheable} + {@code sync} 触发同步锁处理器</li>
 *   <li>{@code @RedisCacheable} + {@code ttl} 触发 TTL 处理器,Redis 实际 TTL 匹配</li>
 * </ul>
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Import(TestRedisConfiguration.class)
@DisplayName("AOP 行为回归契约")
class PathCAopContractIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired
    private TestCacheService cacheService;

    @Autowired
    private RedisTemplate<String, Object> redisCacheTemplate;

    @Autowired
    private io.github.davidhlp.spring.cache.redis.cache.BloomSupport bloomSupport;

    @BeforeEach
    void setUp() {
        redisCacheTemplate.getConnectionFactory().getConnection().flushDb();
        cacheService.reset();
    }

    @Nested
    @DisplayName("纯 Spring @Cacheable 经 ResiCache 链路")
    class PureSpringCacheableTests {

        @Test
        @DisplayName("PathC-Step0-1: 纯 @Cacheable 走 ResiCache 链,二次调用不重入方法")
        void pureCacheable_cachesThroughResiCacheChain() {
            String r1 = cacheService.getByIdWithPureSpring(1L);
            String r2 = cacheService.getByIdWithPureSpring(1L);
            assertThat(r1).isEqualTo("pure-1");
            assertThat(r2).isEqualTo("pure-1");
            assertThat(cacheService.getCallCount())
                    .as("第二次调用应命中缓存,方法体不重入")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("@RedisCacheable 链处理器契约")
    class ChainHandlerTests {

        @Test
        @DisplayName("PathC-Step0-2: useBloomFilter=true 走链(布隆处理器触发)")
        void bloomFilter_handlerFired() {
            cacheService.getByIdWithBloomFilter(1L);
            cacheService.getByIdWithBloomFilter(1L);
            assertThat(cacheService.getCallCount())
                    .as("BloomFilterHandler + ActualCacheHandler 应保证二次调用命中,方法不重入")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("PathC-Step0-3: sync=true 走链(同步锁处理器触发)")
        void sync_handlerFired() {
            cacheService.getByIdWithSync(1L);
            cacheService.getByIdWithSync(1L);
            assertThat(cacheService.getCallCount())
                    .as("SyncLockHandler + ActualCacheHandler 应保证二次调用命中,方法不重入")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("PathC-Step0-4: ttl=120 走链(TTL 处理器触发),Redis 实际 TTL 匹配")
        void ttl_handlerFiredAndAppliedToRedis() {
            cacheService.getByIdWithTtl(1L);
            Long actualTtl = redisCacheTemplate.getExpire("testCache::1", TimeUnit.SECONDS);
            // @RedisCacheable(ttl=120) 未设 randomTtl,DefaultTtlPolicy.calculateFinalTtl
            // 在 randomTtl=false 分支直接返回 baseTtl=120;allow 1s 漂移防极端时钟。
            assertThat(actualTtl)
                    .as("Redis 实际 TTL 应在 [119, 120] 秒(TtlHandler 未开 randomTtl)")
                    .isBetween(119L, 120L);
        }
    }

    @Nested
    @DisplayName("sync + bloom 组合(键漂移回归)")
    class SyncPlusBloomTests {

        @Test
        @DisplayName("预热 bloom 后 sync+bloom 用 actualKey 命中,返回真实值非 null")
        void syncPlusBloom_warmBloom_usesActualKey_returnsValue() {
            // createCacheKey(1L) = "testCache::1" → actualKey = "1"(与链层 BloomFilterHandler.add 同源)
            bloomSupport.add("testCache", "1");

            String result = cacheService.getByIdWithSyncAndBloom(1L);

            assertThat(result)
                    .as("sync+bloom 预热 actualKey=1 后,loader 前置 bloom 须命中(actualKey) → 继续加载。"
                            + "键漂移(查带前缀 testCache::1)会静默返回 null,违反 @Cacheable。")
                    .isEqualTo("sync-bloom-1");
            assertThat(cacheService.getCallCount())
                    .as("首次 miss → loader 被调用")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("ADR-01 CLEAN 不改变 Bloom(marker-free 语义)")
    class CleanDoesNotChangeBloomTests {

        @Test
        @DisplayName("CLEAN→GET: 缓存被清但 loader 仍可达,返回真实值")
        void cleanThenGet_loaderStillReachable() {
            cacheService.getByIdWithBloomFilter(1L);
            assertThat(cacheService.getCallCount()).isEqualTo(1);

            // 缓存数据层清空(布隆位保留 — ADR-01: CLEAN 不清布隆)
            cacheService.evictAll();
            assertThat(redisCacheTemplate.opsForValue().get("testCache::1")).isNull();

            // CLEAN 后同一 key GET:布隆 must 不短路 loader
            String result = cacheService.getByIdWithBloomFilter(1L);

            assertThat(result).isEqualTo("bloom-value-1");
            assertThat(cacheService.getCallCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("bloom 启用的 CLEAN(allEntries)不触发布隆 clear")
        void bloomEnabledClean_doesNotClearBloom() {
            cacheService.getByIdWithBloomFilter(1L);
            assertThat(bloomSupport.mightContain("testCache", "1")).isTrue();

            cacheService.evictAllWithBloom();

            assertThat(bloomSupport.mightContain("testCache", "1"))
                    .as("CLEAN(即便启用 bloom)不清布隆 — 位保留,仅数据层清空")
                    .isTrue();
            assertThat(redisCacheTemplate.opsForValue().get("testCache::1")).isNull();
        }

        @Test
        @DisplayName("CLEAN 不产生 rebuilding marker 键(无 marker I/O)")
        void clean_producesNoRebuildMarkerKeys() {
            cacheService.getByIdWithBloomFilter(1L);
            cacheService.evictAll();

            assertThat(redisCacheTemplate.keys("*rebuild*")).isEmpty();
        }
    }
}
