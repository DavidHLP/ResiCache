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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
                null,   // redisCacheRegister disabled
                null,   // syncSupport disabled
                null);  // methodMetadataResolver disabled
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
}
