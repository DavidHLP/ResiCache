package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.filter;

import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.BloomFilterConfig;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.strategy.BloomHashStrategy;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.strategy.MessageDigestBloomHashStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RedisBloomIFilter 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisBloomIFilter Tests")
class RedisBloomIFilterTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private BloomFilterConfig config;
    private BloomHashStrategy hashStrategy;
    private RedisBloomIFilter filter;

    @BeforeEach
    void setUp() {
        config = new BloomFilterConfig("bf:", 1024, 3, 100);
        hashStrategy = new MessageDigestBloomHashStrategy();
        filter = new RedisBloomIFilter(redisTemplate, config, hashStrategy, null);
        filter.init();
    }

    private void resetMock() {
        reset(redisTemplate, hashOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Nested
    @DisplayName("add")
    class AddTests {

        @Test
        @DisplayName("adds key to redis bloom filter using pipeline")
        void add_validKey_addsWithPipeline() {
            when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of());

            filter.add("test-cache", "test-key");

            verify(redisTemplate).executePipelined(any(RedisCallback.class));
        }

        @Test
        @DisplayName("handles null cacheName gracefully")
        void add_nullCacheName_doesNotThrow() {
            filter.add(null, "key");
            // Should not throw or call redis
            verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
        }

        @Test
        @DisplayName("handles null key gracefully")
        void add_nullKey_doesNotThrow() {
            filter.add("cache", null);
            // Should not throw or call redis
            verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
        }

        @Test
        @DisplayName("logs debug message on successful add")
        void add_success_logsDebug() {
            when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of());

            filter.add("test-cache", "test-key");

            // Should complete without exception
            assertThat(filter.mightContain("test-cache", "test-key")).isTrue();
        }
    }

    @Nested
    @DisplayName("mightContain")
    class MightContainTests {

        @Test
        @DisplayName("returns true when all hash positions exist")
        void mightContain_allPositionsExist_returnsTrue() {
            // Simulate all positions existing in Redis
            List<Object> existingPositions = List.of("1", "1", "1");
            when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(existingPositions);

            boolean result = filter.mightContain("test-cache", "test-key");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false when any hash position is missing")
        void mightContain_anyPositionMissing_returnsFalse() {
            // Simulate one position missing (null)
            List<Object> positionsWithNull = new ArrayList<>();
            positionsWithNull.add("1");
            positionsWithNull.add(null);
            positionsWithNull.add("1");
            when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(positionsWithNull);

            boolean result = filter.mightContain("test-cache", "test-key");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("handles null cacheName gracefully")
        void mightContain_nullCacheName_returnsFalse() {
            boolean result = filter.mightContain(null, "key");
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("handles null key gracefully")
        void mightContain_nullKey_returnsFalse() {
            boolean result = filter.mightContain("cache", null);
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns true on exception to avoid false negatives")
        void mightContain_exception_returnsTrue() {
            when(redisTemplate.executePipelined(any(RedisCallback.class)))
                    .thenThrow(new RuntimeException("Redis error"));

            boolean result = filter.mightContain("test-cache", "test-key");

            // Should return true to avoid rejecting potentially valid keys
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false when all positions are null")
        void mightContain_allPositionsNull_returnsFalse() {
            List<Object> allNull = new ArrayList<>();
            allNull.add(null);
            allNull.add(null);
            allNull.add(null);
            when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(allNull);

            boolean result = filter.mightContain("test-cache", "test-key");

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("clear")
    class ClearTests {

        @Test
        @DisplayName("deletes bloom filter key from Redis")
        void clear_existingCache_deletesKey() {
            when(redisTemplate.delete(anyString())).thenReturn(true);

            filter.clear("test-cache");

            verify(redisTemplate).delete("bf:test-cache");
        }

        @Test
        @DisplayName("handles null cacheName gracefully")
        void clear_nullCacheName_doesNotThrow() {
            filter.clear(null);
            // Should not throw or call redis
            verify(redisTemplate, never()).delete(anyString());
        }

        @Test
        @DisplayName("handles exception during delete gracefully")
        void clear_exception_doesNotThrow() {
            when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("Redis error"));

            filter.clear("test-cache");

            // Should not throw
        }
    }

    @Nested
    @DisplayName("False Positive Scenario")
    class FalsePositiveTests {

        @Test
        @DisplayName("returns true for key that might be in filter")
        void mightContain_keyMightExist_returnsTrue() {
            // All positions exist - could be a real entry or false positive
            List<Object> allExist = List.of("1", "1", "1");
            when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(allExist);

            boolean result = filter.mightContain("test-cache", "some-key");

            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("False Negative Prevention")
    class FalseNegativePreventionTests {

        @Test
        @DisplayName("returns true on Redis error to prevent false negatives")
        void mightContain_redisError_returnsTrue() {
            when(redisTemplate.executePipelined(any(RedisCallback.class)))
                    .thenThrow(new RuntimeException("Connection failed"));

            boolean result = filter.mightContain("test-cache", "test-key");

            // Should return true to avoid incorrectly rejecting valid keys
            assertThat(result).isTrue();
        }
    }
}
