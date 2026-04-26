package io.github.davidhlp.spring.cache.redis.core;

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
        cache = new RedisProCache("testCache", cacheWriter, cacheConfiguration, meterRegistry);
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

            doNothing().when(cacheWriter).remove(anyString(), any(byte[].class));

            cache.evict(key);

            verify(cacheWriter).remove(anyString(), any(byte[].class));
        }

        @Test
        @DisplayName("evict does not decrement size below zero")
        void evict_withZeroSize_doesNotGoNegative() {
            String key = "key1";

            doNothing().when(cacheWriter).remove(anyString(), any(byte[].class));

            cache.evict(key);
            cache.evict(key);

            assertThat(cache.getSize()).isEqualTo(0);
        }

        @Test
        @DisplayName("evict records timer")
        void evict_recordsTimer() {
            String key = "key1";

            doNothing().when(cacheWriter).remove(anyString(), any(byte[].class));

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
        @DisplayName("clear delegates to cache writer and resets size")
        void clear_delegatesToWriterAndResetsSize() {
            doNothing().when(cacheWriter).clean(anyString(), any(byte[].class));

            cache.clear();

            verify(cacheWriter).clean(anyString(), any(byte[].class));
            assertThat(cache.getSize()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Hit Rate Tests")
    class HitRateTests {

        @Test
        @DisplayName("getHitRate returns 0 when no requests")
        void getHitRate_withNoRequests_returnsZero() {
            assertThat(cache.getHitRate()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Metrics Tests")
    class MetricsTests {

        @Test
        @DisplayName("cache registers meter with correct tag")
        void cache_registersMeterWithCorrectTag() {
            Timer getTimer = meterRegistry.find("resicache.cache.get").timer();
            Timer putTimer = meterRegistry.find("resicache.cache.put").timer();
            Timer evictTimer = meterRegistry.find("resicache.cache.evict").timer();

            assertThat(getTimer).isNotNull();
            assertThat(putTimer).isNotNull();
            assertThat(evictTimer).isNotNull();
        }
    }

    @Nested
    @DisplayName("Counter Tests")
    class CounterTests {

        @Test
        @DisplayName("initial hit count is zero")
        void initialHitCount_isZero() {
            assertThat(cache.getHitCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("initial miss count is zero")
        void initialMissCount_isZero() {
            assertThat(cache.getMissCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("initial size is zero")
        void initialSize_isZero() {
            assertThat(cache.getSize()).isEqualTo(0);
        }
    }
}
