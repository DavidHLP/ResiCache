package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.cache.CacheMetrics;
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
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RedisProCache Tests")
class RedisProCacheTest {

    @Mock
    private RedisCacheWriter cacheWriter;

    private RedisCacheConfiguration cacheConfiguration;
    private MeterRegistry meterRegistry;
    private RedisProCache cache;

    @BeforeEach
    void setUp() {
        cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig();
        meterRegistry = new SimpleMeterRegistry();
        cache = new RedisProCache(
                "testCache",
                cacheWriter,
                cacheConfiguration,
                ResiCacheFeatures.builder()
                        .meterRegistry(meterRegistry)
                        .build());   // bloom/sync disabled; operationResolver null
    }

    private Callable<String> createLoader(String value) {
        return () -> value;
    }

    @Nested
    @DisplayName("put() Tests")
    class PutTests {

        @Test
        @DisplayName("put delegates to cache writer")
        void put_delegatesToWriter() {
            String key = "key1";
            Object value = "value";

            doNothing().when(cacheWriter).put(anyString(), any(byte[].class), any(byte[].class), any());

            cache.put(key, value);

            verify(cacheWriter).put(anyString(), any(byte[].class), any(byte[].class), any());
        }

        @Test
        @DisplayName("put records timer")
        void put_recordsTimer() {
            String key = "key1";
            Object value = "value";

            doNothing().when(cacheWriter).put(anyString(), any(byte[].class), any(byte[].class), any());

            cache.put(key, value);

            Timer timer = meterRegistry.find("resicache.cache.put").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("evict() Tests")
    class EvictTests {

        @Test
        @DisplayName("evict delegates to cache writer")
        void evict_delegatesToWriter() {
            String key = "key1";

            doNothing().when(cacheWriter).evict(anyString(), any(byte[].class));

            cache.evict(key);

            verify(cacheWriter).evict(anyString(), any(byte[].class));
        }

        @Test
        @DisplayName("evict increments evict counter")
        void evict_incrementsCounter() {
            String key = "key1";

            doNothing().when(cacheWriter).evict(anyString(), any(byte[].class));

            cache.evict(key);

            Counter counter = meterRegistry.find("resicache.cache.evict.count").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("evict records timer")
        void evict_recordsTimer() {
            String key = "key1";

            doNothing().when(cacheWriter).evict(anyString(), any(byte[].class));

            cache.evict(key);

            Timer timer = meterRegistry.find("resicache.cache.evict").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("clear() Tests")
    class ClearTests {

        @Test
        @DisplayName("clear delegates to cache writer")
        void clear_delegatesToWriter() {
            doNothing().when(cacheWriter).clean(anyString(), any(byte[].class));

            cache.clear();

            verify(cacheWriter).clean(anyString(), any(byte[].class));
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
            Timer getTimer = meterRegistry.find("resicache.cache.get").tag("cache", "testCache").timer();
            Timer putTimer = meterRegistry.find("resicache.cache.put").tag("cache", "testCache").timer();
            Timer evictTimer = meterRegistry.find("resicache.cache.evict").tag("cache", "testCache").timer();

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

    // ==================== ADR-0062 / Round 49: get(key, loader) 编排集成测试 ====================
    // 注:bloom 短路 / sync 路由 / locked-load 3 决策分支的细粒度单元测试已迁出至
    // LoaderOrchestratorTest(orchestrator 自身即可零 RedisProCache fixture 单测);
    // 本测试类专注于 RedisProCache 与 orchestrator 的集成:miss counter 自增 / putAfterLoad
    // 走 override 保留 putTimer + putCounter / 异常翻译规则等 RedisProCache 侧契约。

    @Nested
    @DisplayName("get(key, loader) Integration Tests — ADR-0062 orchestrator delegation")
    class GetWithLoaderIntegrationTests {

        @Mock
        private BloomSupport bloomSupport;

        @Mock
        private CacheOperationResolver operationResolver;

        /**
         * 构造启用 bloom 但 operationResolver 可控的 cache — operationResolver.resolve
         * 返回带 useBloomFilter=true 的 operation,触发 orchestrator 走 bloom 短路检查路径。
         */
        private RedisProCache buildCacheWithBloomAndResolver(BloomGate bloomGate,
                                                             RedisCacheableOperation returnedOperation) {
            when(operationResolver.resolve(eq("testCache"))).thenReturn(returnedOperation);
            return new RedisProCache(
                    "testCache", cacheWriter, cacheConfiguration,
                    ResiCacheFeatures.builder()
                            .meterRegistry(meterRegistry)
                            .operationResolver(operationResolver)
                            .bloomGate(bloomGate)
                            .build());
        }

        @Test
        @DisplayName("bloom short-circuit → returns null, increments miss counter exactly once")
        void bloomShortCircuit_incrementsMissOnce() {
            RedisProCache cacheWithBloom = buildCacheWithBloomAndResolver(
                    new BloomGate(bloomSupport),
                    RedisCacheableOperation.builder()
                            .name("testCache")
                            .cacheNames("testCache")
                            .useBloomFilter(true)
                            .build());

            when(bloomSupport.mightContain(eq("testCache"), anyString())).thenReturn(false);

            double beforeMiss = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", "testCache").counter().count();

            String result = cacheWithBloom.get("key1", () -> {
                throw new AssertionError("loader should not be invoked on bloom short-circuit");
            });

            assertThat(result).isNull();
            double afterMiss = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", "testCache").counter().count();
            // bloom 短路路径:miss counter 自增恰好 1 次(在 switch 的 BloomShortCircuited case)
            assertThat(afterMiss - beforeMiss).isEqualTo(1.0);
        }

        @Test
        @DisplayName("bloom accepts → falls through to default load path")
        void bloomAccepts_fallsThroughToDefaultPath() {
            RedisProCache cacheWithBloom = buildCacheWithBloomAndResolver(
                    new BloomGate(bloomSupport),
                    RedisCacheableOperation.builder()
                            .name("testCache")
                            .cacheNames("testCache")
                            .useBloomFilter(true)
                            .build());

            when(bloomSupport.mightContain(eq("testCache"), anyString())).thenReturn(true);
            // cache miss:2-arg variant 返回 null
            when(cacheWriter.get(anyString(), any(byte[].class))).thenReturn(null);
            // 5-arg variant(RedisCache.get(Object, Callable) 内部用):模拟 load 路径
            // valueLoader.get() 会触发 loader.call() 然后序列化返回值
            when(cacheWriter.get(anyString(), any(byte[].class), any(java.util.function.Supplier.class), any(), any(Boolean.class)))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<byte[]> supplier = inv.getArgument(2);
                        return supplier.get();
                    });

            double beforeMiss = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", "testCache").counter().count();

            String result = cacheWithBloom.get("key1", () -> "loaded-value");

            assertThat(result).isEqualTo("loaded-value");
            // bloom 接受路径走 default load 成功,RedisProCache Loaded outcome 不触发 miss 自增
            double afterMiss = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", "testCache").counter().count();
            assertThat(afterMiss).isEqualTo(beforeMiss);
            // 注:default path 的内部 put 由 Spring RedisCache.get(Object, Callable) 完成,
            // 不走 RedisProCache.put override,因此 putCounter 不自增(与 sync 路径不同)。
        }

        @Test
        @DisplayName("default path with loader throwing → wraps in ValueRetrievalException, increments miss counter")
        void defaultPath_loaderThrows_incrementsMissOnce() {
            // operationResolver 默认为 null → lookupOperation 返回 null → orchestrator 走 default path
            RuntimeException loaderEx = new RuntimeException("loader failed");
            // 5-arg variant:loader 抛 RuntimeException,Spring 翻译为 ValueRetrievalException
            when(cacheWriter.get(anyString(), any(byte[].class), any(java.util.function.Supplier.class), any(), any(Boolean.class)))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<byte[]> supplier = inv.getArgument(2);
                        return supplier.get();  // 调 loader + serialize,loader 抛 RuntimeException
                    });
            // 2-arg variant
            when(cacheWriter.get(anyString(), any(byte[].class))).thenReturn(null);

            double beforeMiss = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", "testCache").counter().count();

            // Spring 把 Exception 在 super.get 内部包成 ValueRetrievalException(RedisCache.loadCacheValue 行为)
            // RedisProCache orchestrator 收到 LoadFailed,switch 翻译为 throw ValueRetrievalException
            assertThatThrownBy(() -> cache.get("key1", () -> {
                throw loaderEx;
            }))
                    .isInstanceOf(org.springframework.cache.Cache.ValueRetrievalException.class)
                    .hasCauseReference(loaderEx);

            double afterMiss = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", "testCache").counter().count();
            // LoadFailed 路径:RedisProCache switch LoadFailed case 自增 miss + 直接抛
            assertThat(afterMiss - beforeMiss).isEqualTo(1.0);
        }
    }
}
