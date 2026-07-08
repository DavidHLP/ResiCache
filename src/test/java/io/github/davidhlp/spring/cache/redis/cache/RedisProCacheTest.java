package io.github.davidhlp.spring.cache.redis.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.davidhlp.spring.cache.redis.cache.CacheMetrics;
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
import static org.mockito.Mockito.*;

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
                meterRegistry,
                null,   // bloomSupport disabled
                null,   // operationResolver disabled (ADR-0057 seam; nullable → no metadata lookup)
                null);  // syncSupport disabled
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

            // 验证 evict 计数器自增为 1(而非仅断言 counter 存在)
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
            // ADR-0047 / C2:hitRate 算术收敛到 CacheMetrics record,测试断言单一方法。
            assertThat(cache.metrics().hitRate()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Metrics Tests")
    class MetricsTests {

        @Test
        @DisplayName("cache registers meter with correct tag")
        void cache_registersMeterWithCorrectTag() {
            // 用 tag("cache","testCache") 精确查找,验证 meter 带 correct tag(而非仅断言 meter 存在)
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
            // ADR-0047 / C2:5 个 getter 合并到单一 metrics() seam,测试断言整个值对象。
            CacheMetrics snapshot = cache.metrics();
            assertThat(snapshot.hitCount()).isEqualTo(0);
            assertThat(snapshot.missCount()).isEqualTo(0);
            assertThat(snapshot.putCount()).isEqualTo(0);
            assertThat(snapshot.evictCount()).isEqualTo(0);
            assertThat(snapshot.hitRate()).isEqualTo(0.0);
        }
    }

    /**
     * ADR-0057 抽出的 performLockedLoad 单测 — 覆盖原 12 行内联 lambda 的 3 决策分支
     * (existing-value fast-path / null-value 缓存 / loader 异常翻译)。
     * 直接调方法,绕过 syncSupport.executeSync 调度,验证持锁后单飞契约。
     */
    @Nested
    @DisplayName("performLockedLoad tests — ADR-0057 single-flight seam")
    class PerformLockedLoadTests {

        @Test
        @DisplayName("returns existing value without invoking loader when cache is hit")
        void performLockedLoad_existingValue_returnsCachedSkipsLoader() throws Exception {
            // 预序列化一个 String 值,模拟已存在的缓存
            String existing = "cached-value";
            java.nio.ByteBuffer buffer = cacheConfiguration.getValueSerializationPair()
                    .getWriter().write(existing);
            byte[] cachedBytes = new byte[buffer.remaining()];
            buffer.get(cachedBytes);
            when(cacheWriter.get(eq("testCache"), any(byte[].class))).thenReturn(cachedBytes);

            Callable<String> loader = mock(Callable.class);
            String result = cache.performLockedLoad("key1", loader);

            assertThat(result).isEqualTo(existing);
            verify(loader, never()).call();
            // 命中已有值时不应再 put
            verify(cacheWriter, never()).put(anyString(), any(byte[].class), any(byte[].class), any());
        }

        @Test
        @DisplayName("invokes loader and puts result on cache miss (null-value allowed)")
        void performLockedLoad_cacheMiss_invokesLoaderAndPuts() throws Exception {
            // cacheWriter.get 返回 null → 走 load + put 路径
            when(cacheWriter.get(eq("testCache"), any(byte[].class))).thenReturn(null);
            doNothing().when(cacheWriter).put(anyString(), any(byte[].class), any(byte[].class), any());

            Callable<String> loader = () -> "loaded-value";
            String result = cache.performLockedLoad("key2", loader);

            assertThat(result).isEqualTo("loaded-value");
            // 调了 put
            verify(cacheWriter).put(eq("testCache"), any(byte[].class), any(byte[].class), any());
        }

        @Test
        @DisplayName("invokes loader and puts null value when loader returns null (null-value caching)")
        void performLockedLoad_loaderReturnsNull_putsNull() throws Exception {
            when(cacheWriter.get(eq("testCache"), any(byte[].class))).thenReturn(null);
            doNothing().when(cacheWriter).put(anyString(), any(byte[].class), any(byte[].class), any());

            Callable<String> loader = () -> null;
            String result = cache.performLockedLoad("key3", loader);

            assertThat(result).isNull();
            // 即使 null 也 put(由 RedisCache 配置处理空值缓存)
            verify(cacheWriter).put(eq("testCache"), any(byte[].class), any(byte[].class), any());
        }

        @Test
        @DisplayName("translates loader's checked exception to ValueRetrievalException")
        void performLockedLoad_loaderThrows_wrapsInValueRetrievalException() {
            when(cacheWriter.get(eq("testCache"), any(byte[].class))).thenReturn(null);

            Exception loaderException = new RuntimeException("loader failed");
            Callable<String> loader = () -> { throw loaderException; };

            assertThatThrownBy(() -> cache.performLockedLoad("key4", loader))
                    .isInstanceOf(org.springframework.cache.Cache.ValueRetrievalException.class)
                    .hasMessageContaining("key4");

            // 异常翻译后不调 put
            verify(cacheWriter, never()).put(anyString(), any(byte[].class), any(byte[].class), any());
        }
    }

    /**
     * ADR-0057 / C3 抽出的 isBloomShortCircuited 单测 — 覆盖 get(key, loader) 的 bloom 守门决策 4 分支。
     */
    @Nested
    @DisplayName("C3 isBloomShortCircuited seam tests")
    class C3IsBloomShortCircuitedTests {

        @Mock
        private io.github.davidhlp.spring.cache.redis.protection.bloom.BloomSupport bloomSupport;

        @Test
        @DisplayName("returns false when operation is null")
        void isBloomShortCircuited_nullOperation_returnsFalse() {
            boolean result = cache.isBloomShortCircuited(null, "key1");
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when isUseBloomFilter is false on operation")
        void isBloomShortCircuited_bloomDisabledOnOperation_returnsFalse() {
            io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation operation =
                    io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation.builder()
                            .name("test-cache")
                            .cacheNames("test-cache")
                            .useBloomFilter(false)
                            .build();

            boolean result = cache.isBloomShortCircuited(operation, "key1");
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns true and increments miss when bloom rejects key")
        void isBloomShortCircuited_bloomRejects_returnsTrueAndIncrementsMiss() {
            // 重新构造 cache 启用 bloomSupport(默认 cache 是 null,bloom 分支会直接 short-circuit 到 false)
            RedisProCache cacheWithBloom = new RedisProCache(
                    "testCache", cacheWriter, cacheConfiguration, meterRegistry,
                    bloomSupport, null, null);

            io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation operation =
                    io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation.builder()
                            .name("test-cache")
                            .cacheNames("test-cache")
                            .useBloomFilter(true)
                            .build();
            when(bloomSupport.mightContain(eq("testCache"), anyString())).thenReturn(false);

            double beforeCount = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", "testCache").counter().count();
            boolean result = cacheWithBloom.isBloomShortCircuited(operation, "key1");

            assertThat(result).isTrue();
            // miss counter 自增 1
            double afterCount = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", "testCache").counter().count();
            assertThat(afterCount - beforeCount).isEqualTo(1.0);
        }

        @Test
        @DisplayName("returns false when bloom accepts key (no miss side effect)")
        void isBloomShortCircuited_bloomAccepts_returnsFalseNoSideEffect() {
            RedisProCache cacheWithBloom = new RedisProCache(
                    "testCache", cacheWriter, cacheConfiguration, meterRegistry,
                    bloomSupport, null, null);

            io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation operation =
                    io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation.builder()
                            .name("test-cache")
                            .cacheNames("test-cache")
                            .useBloomFilter(true)
                            .build();
            when(bloomSupport.mightContain(eq("testCache"), anyString())).thenReturn(true);

            double beforeCount = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", "testCache").counter().count();
            boolean result = cacheWithBloom.isBloomShortCircuited(operation, "key1");

            assertThat(result).isFalse();
            // 接受路径不应自增 miss
            double afterCount = meterRegistry.find("resicache.cache.miss")
                    .tag("cache", "testCache").counter().count();
            assertThat(afterCount).isEqualTo(beforeCount);
        }
    }

    /**
     * ADR-0057 / C3 抽出的 loadValue 单测 — 重点验证 sync 路由决策;
     * default 分支(super.get)由 Spring RedisCache 默认行为保证,本测试不重复覆盖
     * (PerformLockedLoadTests 已通过 performLockedLoad 间接覆盖 super.get 的 lookup 路径)。
     */
    @Nested
    @DisplayName("C3 loadValue seam tests")
    class C3LoadValueTests {

        @Mock
        private io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncSupport syncSupport;

        @Test
        @DisplayName("sync enabled and syncSupport available → routes to executeSyncLoad (via syncSupport.executeSync)")
        void loadValue_syncEnabledWithSyncSupport_routesToExecuteSyncLoad() {
            // 构造启用 syncSupport 的 cache
            RedisProCache cacheWithSync = new RedisProCache(
                    "testCache", cacheWriter, cacheConfiguration, meterRegistry,
                    null, null, syncSupport);
            io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation operation =
                    io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation.builder()
                            .name("test-cache")
                            .cacheNames("test-cache")
                            .sync(true)
                            .build();
            // syncSupport.executeSync 直接调 loader → 返回 "synced-value"
            when(syncSupport.executeSync(anyString(), any(java.util.function.Supplier.class), anyLong()))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<String> supplier = inv.getArgument(1);
                        return supplier.get();
                    });

            Callable<String> loader = () -> "synced-value";

            String result = cacheWithSync.loadValue("key3", loader, operation);

            assertThat(result).isEqualTo("synced-value");
            // 走 executeSyncLoad → 调了 syncSupport.executeSync
            verify(syncSupport).executeSync(anyString(), any(java.util.function.Supplier.class), anyLong());
        }
    }
}
