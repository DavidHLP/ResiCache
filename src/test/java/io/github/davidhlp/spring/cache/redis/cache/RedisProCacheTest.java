package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.cache.loader.CacheOperationResolver;
import io.github.davidhlp.spring.cache.redis.cache.metrics.CacheMetrics;
import io.github.davidhlp.spring.cache.redis.cache.model.CachedValue;
import io.github.davidhlp.spring.cache.redis.cache.model.ResiCacheFeatures;
import io.github.davidhlp.spring.cache.redis.integration.AbstractRedisIntegrationTest;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomGate;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomSupport;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * RedisProCache 测试 — 真实 Redis 数据通路(原为 Mockito 单元测试)。
 *
 * <p><b>为什么改用真实 Redis:</b> 原版 mock 了 {@link org.springframework.data.redis.cache.RedisCacheWriter}
 * (即 RedisProCacheWriter),put/evict/clear 用 {@code doNothing().when(cacheWriter).put(...)} 把写入变成空操作,
 * 再 {@code verify(cacheWriter).put(...)} 验证"委托"。这是假阳性:验证的是"调用了 mock 方法",而非
 * "值真正写入 Redis"。本版用真实 {@link RedisProCacheWriter} bean —— put/evict/clear 经真实责任链落盘,
 * 断言改为验证 Redis 中的真实状态 + 计量。
 *
 * <p><b>转换边界:</b>
 * <ul>
 *   <li>Redis 数据通路(RedisProCacheWriter)→ 真实 bean。</li>
 *   <li>{@link BloomSupport}(布隆防护机制,非终端 Redis I/O)→ 保留 mock:bloom 短路测的是 orchestrator
 *       的分支接线(bloom 否决 → loader 不调用 → miss+1),bloom 自身的 Redis 位运算在
 *       RedisBloomIFilterTest 单独验证。此处 mock bloom 是协作对象桩,非 Redis 数据通路假阳性。</li>
 *   <li>{@link MeterRegistry} 用 {@link SimpleMeterRegistry}:测试需精确断言特定 meter,真实 registry
 *       不影响 Redis 行为。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RedisProCache Tests (real Redis data path)")
class RedisProCacheTest extends AbstractRedisIntegrationTest {

    @Autowired
    private RedisProCacheWriter realWriter;

    @Autowired
    private RedisCacheConfiguration cacheConfiguration;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ValueOperations<String, Object> valueOperations;


    private MeterRegistry meterRegistry;
    private RedisProCache cache;

    private static final String NAME = "testCache";
    private static final String REDIS_KEY = "testCache::key1";

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushDb();
        meterRegistry = new SimpleMeterRegistry();
        cache = new RedisProCache(
                NAME,
                realWriter,
                cacheConfiguration,
                ResiCacheFeatures.builder()
                        .meterRegistry(meterRegistry)
                        .build());   // bloom/sync disabled; operationResolver null
    }

    @Nested
    @DisplayName("put() Tests")
    class PutTests {

        @Test
        @DisplayName("put persists value to Redis via the real writer")
        void put_persistsToRedis() {
            cache.put("key1", "value");

            // 真实:值经真实 writer + 真实责任链落盘(原版 verify(mock).put,此处验证真实副作用)
            assertThat(redisTemplate.hasKey(REDIS_KEY)).isTrue();
            Object stored = valueOperations.get(REDIS_KEY);
            assertThat(stored).isInstanceOf(CachedValue.class);
        }

        @Test
        @DisplayName("put records timer")
        void put_recordsTimer() {
            cache.put("key1", "value");

            Timer timer = meterRegistry.find("resicache.cache.put").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("evict() Tests")
    class EvictTests {

        @Test
        @DisplayName("evict deletes the key from Redis via the real writer")
        void evict_deletesFromRedis() {
            valueOperations.set(REDIS_KEY, CachedValue.of("value", 60));

            cache.evict("key1");

            // 真实:key 经真实 writer 从 Redis 删除
            assertThat(redisTemplate.hasKey(REDIS_KEY)).isFalse();
        }

        @Test
        @DisplayName("evict increments evict counter")
        void evict_incrementsCounter() {
            cache.evict("key1");

            Counter counter = meterRegistry.find("resicache.cache.evict.count").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("evict records timer")
        void evict_recordsTimer() {
            cache.evict("key1");

            Timer timer = meterRegistry.find("resicache.cache.evict").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("clear() Tests")
    class ClearTests {

        @Test
        @DisplayName("clear removes matching keys from Redis via the real writer")
        void clear_removesFromRedis() {
            valueOperations.set("testCache::a", CachedValue.of("a", 60));
            valueOperations.set("testCache::b", CachedValue.of("b", 60));

            cache.clear();

            // 真实:clear → writer.clean 批量删除匹配前缀的 key
            assertThat(redisTemplate.hasKey("testCache::a")).isFalse();
            assertThat(redisTemplate.hasKey("testCache::b")).isFalse();
        }
    }

    @Nested
    @DisplayName("Hit Rate Tests")
    class HitRateTests {

        @Test
        @DisplayName("metrics.hitRate returns 0 when no requests")
        void metricsHitRate_withNoRequests_returnsZero() {
            assertThat(cache.metrics().hitRate()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Metrics Tests")
    class MetricsTests {

        @Test
        @DisplayName("cache registers meter with correct tag")
        void cache_registersMeterWithCorrectTag() {
            Timer getTimer = meterRegistry.find("resicache.cache.get").tag("cache", NAME).timer();
            Timer putTimer = meterRegistry.find("resicache.cache.put").tag("cache", NAME).timer();
            Timer evictTimer = meterRegistry.find("resicache.cache.evict").tag("cache", NAME).timer();

            assertThat(getTimer).isNotNull();
            assertThat(putTimer).isNotNull();
            assertThat(evictTimer).isNotNull();
        }

        @Test
        @DisplayName("metrics() returns zero snapshot on fresh cache")
        void metrics_returnsZeroSnapshot_onFreshCache() {
            CacheMetrics snapshot = cache.metrics();
            assertThat(snapshot.hitCount()).isEqualTo(0);
            assertThat(snapshot.missCount()).isEqualTo(0);
            assertThat(snapshot.putCount()).isEqualTo(0);
            assertThat(snapshot.evictCount()).isEqualTo(0);
            assertThat(snapshot.hitRate()).isEqualTo(0.0);
        }
    }

    // ==================== get(key, loader) 编排集成测试 ====================
    // orchestrator 的 bloom 短路 / default load / LoadFailed 三分支细粒度单测在
    // LoaderOrchestratorTest;真实 Redis 往返在 CacheOperationsIntegrationTest。
    // 本测试类验证 RedisProCache 与 orchestrator 的集成:miss counter 规则、
    // 异常翻译等 RedisProCache 侧契约 —— 现用真实 writer,Redis 路径真实可达。

    @Nested
    @DisplayName("get(key, loader) Integration Tests — orchestrator delegation")
    class GetWithLoaderIntegrationTests {

        @Mock
        private BloomSupport bloomSupport;

        /**
         * 构造启用 bloom 但 operationResolver 可控的 cache —— operationResolver.resolve
         * 返回带 useBloomFilter=true 的 operation,触发 orchestrator 走 bloom 短路检查路径。
         * 仍用真实 realWriter(Redis 数据通路真实),仅 bloom 协作对象用 mock。
         */
        private RedisProCache buildCacheWithBloomAndResolver(
                io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation returnedOperation) {
            io.github.davidhlp.spring.cache.redis.cache.loader.CacheOperationResolver operationResolver =
                    org.mockito.Mockito.mock(io.github.davidhlp.spring.cache.redis.cache.loader.CacheOperationResolver.class);
            when(operationResolver.resolve(eq(NAME))).thenReturn(returnedOperation);
            return new RedisProCache(
                    NAME, realWriter, cacheConfiguration,
                    ResiCacheFeatures.builder()
                            .meterRegistry(meterRegistry)
                            .operationResolver(operationResolver)
                            .bloomGate(new BloomGate(bloomSupport))
                            .build());
        }

        @Test
        @DisplayName("bloom short-circuit → returns null, increments miss counter exactly once")
        void bloomShortCircuit_incrementsMissOnce() {
            RedisProCache cacheWithBloom = buildCacheWithBloomAndResolver(
                    RedisCacheableOperation.builder()
                            .name(NAME)
                            .cacheNames(NAME)
                            .useBloomFilter(true)
                            .build());

            when(bloomSupport.mightContain(eq(NAME), anyString())).thenReturn(false);

            double beforeMiss = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", NAME).counter().count();

            String result = cacheWithBloom.get("key1", () -> {
                throw new AssertionError("loader should not be invoked on bloom short-circuit");
            });

            assertThat(result).isNull();
            double afterMiss = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", NAME).counter().count();
            // bloom 短路路径:miss counter 自增恰好 1 次
            assertThat(afterMiss - beforeMiss).isEqualTo(1.0);
        }

        @Test
        @DisplayName("bloom accepts → falls through to default load path (real Redis miss → load → persist)")
        void bloomAccepts_fallsThroughToDefaultPath() {
            RedisProCache cacheWithBloom = buildCacheWithBloomAndResolver(
                    RedisCacheableOperation.builder()
                            .name(NAME)
                            .cacheNames(NAME)
                            .useBloomFilter(true)
                            .build());

            // bloom 接受 → orchestrator 走 default load → 真实 Redis 未命中 → 调 loader → 持久化
            when(bloomSupport.mightContain(eq(NAME), anyString())).thenReturn(true);

            double beforeMiss = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", NAME).counter().count();

            String result = cacheWithBloom.get("key1", () -> "loaded-value");

            assertThat(result).isEqualTo("loaded-value");
            // default load 成功路径不触发 miss 自增(与原版契约一致)
            double afterMiss = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", NAME).counter().count();
            assertThat(afterMiss).isEqualTo(beforeMiss);
            // 真实:default load 的内部 put 经 Spring RedisCache.get(Object, Callable) 持久化到 Redis
            assertThat(redisTemplate.hasKey(REDIS_KEY)).isTrue();
        }

        @Test
        @DisplayName("default path with loader throwing → ValueRetrievalException, increments miss counter")
        void defaultPath_loaderThrows_incrementsMissOnce() {
            // operationResolver 默认 null → lookupOperation 返回 null → orchestrator 走 default path
            // 真实 Redis 未命中 → 调 loader → loader 抛 → Spring 翻译为 ValueRetrievalException
            RuntimeException loaderEx = new RuntimeException("loader failed");

            double beforeMiss = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", NAME).counter().count();

            assertThatThrownBy(() -> cache.get("key1", () -> {
                throw loaderEx;
            }))
                    .isInstanceOf(Cache.ValueRetrievalException.class)
                    .hasCauseReference(loaderEx);

            double afterMiss = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", NAME).counter().count();
            // LoadFailed 路径:RedisProCache switch LoadFailed case 自增 miss + 直接抛
            assertThat(afterMiss - beforeMiss).isEqualTo(1.0);
        }
    }
}
