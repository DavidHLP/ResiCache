package io.github.davidhlp.spring.cache.redis.protection.bloom;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
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
 * BloomRebuilder 单元测试 —— ADR-0058 抽出后的独立 seam 覆盖。
 *
 * <p>覆盖原 {@link BloomSupport} 内嵌的 rebuilding 窗口状态机:
 * <ul>
 *   <li>isRebuilding:窗口禁用 / Redis 标志存在 / 本地 Caffeine 缓存 / Redis 异常降级</li>
 *   <li>markRebuilding:窗口禁用 no-op / 写 Redis 标志(带 TTL)/ 异常不抛出</li>
 * </ul>
 *
 * <p>原 {@code BloomSupportTest} 包含的对应断言已迁出至本类;{@link BloomSupportTest}
 * 现在仅覆盖代理 + fail-open 契约(以 {@code BloomRebuilder} mock 为依赖)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BloomRebuilder Tests")
class BloomRebuilderTest {

    private static final String CACHE = "cache";
    private static final String REBUILD_KEY = "resicache:bloom:rebuild:" + CACHE;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisProCacheProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RedisProCacheProperties();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Nested
    @DisplayName("isRebuilding")
    class IsRebuildingTests {

        @Test
        @DisplayName("窗口启用 + Redis 标志存在 → true")
        void isRebuilding_flagPresent_returnsTrue() {
            when(redisTemplate.hasKey(REBUILD_KEY)).thenReturn(true);
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, properties);

            assertThat(rebuilder.isRebuilding(CACHE)).isTrue();
        }

        @Test
        @DisplayName("窗口启用 + Redis 标志缺失 → false")
        void isRebuilding_flagAbsent_returnsFalse() {
            when(redisTemplate.hasKey(REBUILD_KEY)).thenReturn(false);
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, properties);

            assertThat(rebuilder.isRebuilding(CACHE)).isFalse();
        }

        @Test
        @DisplayName("窗口禁用(window=0) → 直接返回 false,不查 Redis")
        void isRebuilding_windowDisabled_returnsFalseWithoutRedisCheck() {
            properties.getBloomFilter().setRebuildWindowSeconds(0);
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, properties);

            assertThat(rebuilder.isRebuilding(CACHE)).isFalse();
            verify(redisTemplate, never()).hasKey(anyString());
        }

        @Test
        @DisplayName("Redis 查询异常 → 降级为 false,不抛出")
        void isRebuilding_redisThrows_returnsFalse() {
            when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("Redis down"));
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, properties);

            assertThat(rebuilder.isRebuilding(CACHE)).isFalse();
        }

        @Test
        @DisplayName("本地 Caffeine 缓存命中后,重复查询不重复打 Redis")
        void isRebuilding_cachedLocally_skipsRedisOnRepeat() {
            when(redisTemplate.hasKey(REBUILD_KEY)).thenReturn(true);
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, properties);

            rebuilder.isRebuilding(CACHE);
            rebuilder.isRebuilding(CACHE);

            verify(redisTemplate, times(1)).hasKey(anyString());
        }

        @Test
        @DisplayName("properties 为 null → 退化为窗口禁用(rebuildWindowSeconds=0)")
        void isRebuilding_nullProperties_disablesWindow() {
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, null);

            assertThat(rebuilder.isRebuilding(CACHE)).isFalse();
            verify(redisTemplate, never()).hasKey(anyString());
        }
    }

    @Nested
    @DisplayName("markRebuilding")
    class MarkRebuildingTests {

        @Test
        @DisplayName("窗口启用 → 写 Redis 标志(带 TTL=window)")
        void markRebuilding_windowEnabled_writesRedisFlagWithTtl() {
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, properties);

            rebuilder.markRebuilding(CACHE);

            verify(valueOps).set(eq(REBUILD_KEY), eq("1"), any(Duration.class));
        }

        @Test
        @DisplayName("窗口禁用 → no-op,不写 Redis")
        void markRebuilding_windowDisabled_skipsRedisWrite() {
            properties.getBloomFilter().setRebuildWindowSeconds(0);
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, properties);

            rebuilder.markRebuilding(CACHE);

            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("写 Redis 标志失败 → 不抛出(退化为无窗口旧行为)")
        void markRebuilding_redisSetThrows_doesNotPropagate() {
            doThrow(new RuntimeException("Redis down"))
                    .when(valueOps).set(anyString(), anyString(), any(Duration.class));
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, properties);

            // 不应抛出:标志失败仅记日志
            rebuilder.markRebuilding(CACHE);
        }
    }

    @Nested
    @DisplayName("getRebuildWindowSeconds")
    class GetRebuildWindowSecondsTests {

        @Test
        @DisplayName("返回配置中的窗口秒数")
        void getRebuildWindowSeconds_returnsConfiguredValue() {
            properties.getBloomFilter().setRebuildWindowSeconds(45);
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, properties);

            assertThat(rebuilder.getRebuildWindowSeconds()).isEqualTo(45);
        }

        @Test
        @DisplayName("properties 为 null → 返回 0(禁用)")
        void getRebuildWindowSeconds_nullProperties_returnsZero() {
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, null);

            assertThat(rebuilder.getRebuildWindowSeconds()).isEqualTo(0L);
        }
    }
}
