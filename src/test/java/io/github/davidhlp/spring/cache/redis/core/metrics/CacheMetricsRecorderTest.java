package io.github.davidhlp.spring.cache.redis.core.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheMetricsRecorder 单元测试
 */
@DisplayName("CacheMetricsRecorder Tests")
class CacheMetricsRecorderTest {

    private CacheMetricsRecorder recorder;

    @BeforeEach
    void setUp() {
        // 使用空的 MeterRegistry
        recorder = new CacheMetricsRecorder(Optional.empty());
    }

    @Nested
    @DisplayName("recordHit")
    class RecordHitTests {

        @Test
        @DisplayName("不抛出异常")
        void recordHit_noException() {
            recorder.recordHit("test-cache");
            // 没有异常即通过
        }

        @Test
        @DisplayName("可以记录多个缓存的命中")
        void recordHit_multipleCaches() {
            recorder.recordHit("cache1");
            recorder.recordHit("cache2");
            recorder.recordHit("cache1");
            // 没有异常即通过
        }
    }

    @Nested
    @DisplayName("recordMiss")
    class RecordMissTests {

        @Test
        @DisplayName("不抛出异常")
        void recordMiss_noException() {
            recorder.recordMiss("test-cache");
        }

        @Test
        @DisplayName("可以记录多个缓存的未命中")
        void recordMiss_multipleCaches() {
            recorder.recordMiss("cache1");
            recorder.recordMiss("cache2");
        }
    }

    @Nested
    @DisplayName("recordLatency")
    class RecordLatencyTests {

        @Test
        @DisplayName("不抛出异常")
        void recordLatency_noException() {
            recorder.recordLatency("test-cache", 100L);
        }

        @Test
        @DisplayName("可以记录零延迟")
        void recordLatency_zeroLatency() {
            recorder.recordLatency("test-cache", 0L);
        }

        @Test
        @DisplayName("可以记录大延迟")
        void recordLatency_largeLatency() {
            recorder.recordLatency("test-cache", 10000L);
        }
    }

    @Nested
    @DisplayName("recordSlowOperation")
    class RecordSlowOperationTests {

        @Test
        @DisplayName("不抛出异常")
        void recordSlowOperation_noException() {
            recorder.recordSlowOperation("test-cache", "GET", "key", 5000L);
        }
    }

    @Nested
    @DisplayName("recordCacheSize")
    class RecordCacheSizeTests {

        @Test
        @DisplayName("不抛出异常")
        void recordCacheSize_noException() {
            recorder.recordCacheSize("test-cache", 100L);
        }

        @Test
        @DisplayName("可以记录零大小")
        void recordCacheSize_zeroSize() {
            recorder.recordCacheSize("test-cache", 0L);
        }

        @Test
        @DisplayName("可以记录大缓存大小")
        void recordCacheSize_largeSize() {
            recorder.recordCacheSize("test-cache", 1000000L);
        }
    }
}
