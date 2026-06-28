package io.github.davidhlp.spring.cache.redis.protection.bloom;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.BloomIFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BloomSupport 单元测试。
 *
 * <p>覆盖 WS-1.2c 的 rebuilding 窗口(fail-open)语义:CLEAN 后窗口期内 mightContain
 * fail-open;窗口由 Redis TTL 结束;window=0 禁用保持旧行为。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BloomSupport Tests")
class BloomSupportTest {

    private static final String CACHE = "cache";
    private static final String REBUILD_KEY = "resicache:bloom:rebuild:" + CACHE;

    @Mock
    private BloomIFilter bloomFilter;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisProCacheProperties properties;

    private BloomSupport bloomSupport;

    @BeforeEach
    void setUp() {
        // 真实配置对象,默认 rebuildWindowSeconds=30(启用 rebuilding 窗口)
        properties = new RedisProCacheProperties();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        bloomSupport = new BloomSupport(bloomFilter, redisTemplate, properties);
    }

    @Nested
    @DisplayName("mightContain")
    class MightContainTests {

        @Test
        @DisplayName("非 rebuilding 期委托给底层过滤器并返回结果")
        void mightContain_delegatesToFilter() {
            when(bloomFilter.mightContain(CACHE, "key")).thenReturn(true);

            boolean result = bloomSupport.mightContain(CACHE, "key");

            assertThat(result).isTrue();
            verify(bloomFilter).mightContain(CACHE, "key");
        }

        @Test
        @DisplayName("过滤器返回 false 时返回 false")
        void mightContain_filterReturnsFalse_returnsFalse() {
            when(bloomFilter.mightContain(CACHE, "key")).thenReturn(false);

            assertThat(bloomSupport.mightContain(CACHE, "key")).isFalse();
        }

        @Test
        @DisplayName("底层过滤器异常时 fail-open 返回 true")
        void mightContain_filterThrows_returnsTrue() {
            when(bloomFilter.mightContain(CACHE, "key")).thenThrow(new RuntimeException("Filter error"));

            assertThat(bloomSupport.mightContain(CACHE, "key")).isTrue();
        }

        @Test
        @DisplayName("rebuilding 期内 fail-open,不查底层 bloom")
        void mightContain_duringRebuilding_failOpens() {
            when(redisTemplate.hasKey(REBUILD_KEY)).thenReturn(true);

            boolean result = bloomSupport.mightContain(CACHE, "key");

            assertThat(result).isTrue();
            verify(bloomFilter, never()).mightContain(anyString(), anyString());
        }

        @Test
        @DisplayName("rebuild-window=0(禁用)时不查 Redis 标志,直接委托")
        void mightContain_windowDisabled_delegatesWithoutRedisCheck() {
            properties.getBloomFilter().setRebuildWindowSeconds(0);
            BloomSupport disabled = new BloomSupport(bloomFilter, redisTemplate, properties);
            when(bloomFilter.mightContain(CACHE, "key")).thenReturn(false);

            assertThat(disabled.mightContain(CACHE, "key")).isFalse();
            verify(redisTemplate, never()).hasKey(anyString());
        }

        @Test
        @DisplayName("rebuilding 状态本地缓存,重复查询不重复打 Redis")
        void mightContain_rebuildFlagCachedLocally() {
            // 第一次查询触发 hasKey 并缓存结果(false);第二次命中本地缓存,不再查 Redis
            when(bloomFilter.mightContain(CACHE, "key")).thenReturn(false);

            bloomSupport.mightContain(CACHE, "key");
            bloomSupport.mightContain(CACHE, "key");

            verify(redisTemplate, times(1)).hasKey(anyString());
            verify(bloomFilter, times(2)).mightContain(CACHE, "key");
        }
    }

    @Nested
    @DisplayName("add")
    class AddTests {

        @Test
        @DisplayName("委托给底层过滤器添加键")
        void add_delegatesToFilter() {
            bloomSupport.add(CACHE, "key");
            verify(bloomFilter).add(CACHE, "key");
        }

        @Test
        @DisplayName("过滤器异常时只记录日志不抛出")
        void add_filterThrows_noException() {
            doThrow(new RuntimeException("Filter error")).when(bloomFilter).add(CACHE, "key");

            bloomSupport.add(CACHE, "key");

            verify(bloomFilter).add(CACHE, "key");
        }
    }

    @Nested
    @DisplayName("clear + rebuilding 窗口 (WS-1.2c)")
    class ClearAndRebuildingTests {

        @Test
        @DisplayName("clear 委托底层并在 Redis 写入 rebuilding 标志(TTL=window)")
        void clear_delegatesAndSetsRebuildingFlag() {
            bloomSupport.clear(CACHE);

            verify(bloomFilter).clear(CACHE);
            verify(valueOps).set(eq(REBUILD_KEY), eq("1"), any(Duration.class));
        }

        @Test
        @DisplayName("底层 clear 异常时仍尝试开启 rebuilding 窗口")
        void clear_filterThrows_stillMarksRebuilding() {
            doThrow(new RuntimeException("clear error")).when(bloomFilter).clear(CACHE);

            bloomSupport.clear(CACHE);

            verify(valueOps).set(eq(REBUILD_KEY), eq("1"), any(Duration.class));
        }

        @Test
        @DisplayName("rebuild-window=0(禁用)时 clear 不写 Redis 标志")
        void clear_windowDisabled_skipsRebuildingFlag() {
            properties.getBloomFilter().setRebuildWindowSeconds(0);
            BloomSupport disabled = new BloomSupport(bloomFilter, redisTemplate, properties);

            disabled.clear(CACHE);

            verify(bloomFilter).clear(CACHE);
            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("clear 后立即 mightContain fail-open(本地缓存失效,查 Redis 见标志)")
        void clear_thenMightContain_failOpensImmediately() {
            when(redisTemplate.hasKey(REBUILD_KEY)).thenReturn(true);

            bloomSupport.clear(CACHE);
            boolean result = bloomSupport.mightContain(CACHE, "key");

            assertThat(result).isTrue();
            verify(bloomFilter, never()).mightContain(anyString(), anyString());
        }

        @Test
        @DisplayName("写 rebuilding 标志失败时 clear 仍完成(退化为无窗口旧行为)")
        void clear_redisFlagSetFails_doesNotThrow() {
            doThrow(new RuntimeException("Redis down"))
                    .when(valueOps).set(anyString(), anyString(), any(Duration.class));

            // 不应抛出:bloom 已清,标志失败仅记日志
            bloomSupport.clear(CACHE);

            verify(bloomFilter).clear(CACHE);
        }
    }
}
