package io.github.davidhlp.spring.cache.redis.protection.bloom;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.integration.AbstractRedisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BloomRebuilder 集成测试 — 真实 Redis 验证 rebuilding 状态机。
 *
 * <p>故障注入测试单独使用 mock，因为真实 Redis 无法按测试要求主动抛出异常。
 */
@DisplayName("BloomRebuilder Tests (real Redis)")
class BloomRebuilderTest extends AbstractRedisIntegrationTest {

    private static final String CACHE = "cache";
    private static final String REBUILD_KEY = "resicache:bloom:rebuild:" + CACHE;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private RedisProCacheProperties properties;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushDb();
        properties = new RedisProCacheProperties();
    }

    @Nested
    @DisplayName("isRebuilding")
    class IsRebuildingTests {

        @Test
        @DisplayName("窗口启用 + Redis 标志存在 → true")
        void isRebuilding_flagPresent_returnsTrue() {
            redisTemplate.opsForValue().set(REBUILD_KEY, "1", Duration.ofSeconds(30));
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, properties);

            assertThat(rebuilder.isRebuilding(CACHE)).isTrue();
        }

        @Test
        @DisplayName("窗口启用 + Redis 标志缺失 → false")
        void isRebuilding_flagAbsent_returnsFalse() {
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, properties);

            assertThat(rebuilder.isRebuilding(CACHE)).isFalse();
        }

        @Test
        @DisplayName("窗口禁用(window=0) → 直接返回 false")
        void isRebuilding_windowDisabled_returnsFalseWithoutRedisCheck() {
            redisTemplate.opsForValue().set(REBUILD_KEY, "1", Duration.ofSeconds(30));
            properties.getBloomFilter().setRebuildWindowSeconds(0);
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, properties);

            assertThat(rebuilder.isRebuilding(CACHE)).isFalse();
        }

        @Test
        @DisplayName("Redis 查询异常 → 降级为 false,不抛出")
        void isRebuilding_redisThrows_returnsFalse() {
            // fault injection — real Redis cannot throw on demand
            RedisTemplate<String, String> throwingTemplate = mock(RedisTemplate.class);
            when(throwingTemplate.hasKey(anyString())).thenThrow(new RuntimeException("Redis down"));
            BloomRebuilder rebuilder = new BloomRebuilder(throwingTemplate, properties);

            assertThat(rebuilder.isRebuilding(CACHE)).isFalse();
        }

        @Test
        @DisplayName("本地 Caffeine 缓存命中后保留第一次 Redis 结果")
        void isRebuilding_cachedLocally_skipsRedisOnRepeat() {
            redisTemplate.opsForValue().set(REBUILD_KEY, "1", Duration.ofSeconds(30));
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, properties);

            assertThat(rebuilder.isRebuilding(CACHE)).isTrue();
            redisTemplate.delete(REBUILD_KEY);

            assertThat(rebuilder.isRebuilding(CACHE)).isTrue();
        }

        @Test
        @DisplayName("properties 为 null → 退化为窗口禁用(rebuildWindowSeconds=0)")
        void isRebuilding_nullProperties_disablesWindow() {
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, null);

            assertThat(rebuilder.isRebuilding(CACHE)).isFalse();
        }
    }

    @Nested
    @DisplayName("markRebuilding")
    class MarkRebuildingTests {

        @Test
        @DisplayName("窗口启用 → 写真实 Redis 标志(带 TTL=window)")
        void markRebuilding_windowEnabled_writesRedisFlagWithTtl() {
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, properties);

            rebuilder.markRebuilding(CACHE);

            assertThat(redisTemplate.hasKey(REBUILD_KEY)).isTrue();
            assertThat(redisTemplate.opsForValue().get(REBUILD_KEY)).isEqualTo("1");
            assertThat(redisTemplate.getExpire(REBUILD_KEY))
                    .isBetween(1L, properties.getBloomFilter().getRebuildWindowSeconds());
            assertThat(rebuilder.isRebuilding(CACHE)).isTrue();
        }

        @Test
        @DisplayName("窗口禁用 → no-op,不写 Redis")
        void markRebuilding_windowDisabled_skipsRedisWrite() {
            properties.getBloomFilter().setRebuildWindowSeconds(0);
            BloomRebuilder rebuilder = new BloomRebuilder(redisTemplate, properties);

            rebuilder.markRebuilding(CACHE);

            assertThat(redisTemplate.hasKey(REBUILD_KEY)).isFalse();
        }

        @Test
        @DisplayName("写 Redis 标志失败 → 不抛出(退化为无窗口旧行为)")
        void markRebuilding_redisSetThrows_doesNotPropagate() {
            // fault injection — real Redis cannot throw on demand
            RedisTemplate<String, String> throwingTemplate = mock(RedisTemplate.class);
            ValueOperations<String, String> throwingOps = mock(ValueOperations.class);
            when(throwingTemplate.opsForValue()).thenReturn(throwingOps);
            doThrow(new RuntimeException("Redis down"))
                    .when(throwingOps).set(anyString(), anyString(), any(Duration.class));
            BloomRebuilder rebuilder = new BloomRebuilder(throwingTemplate, properties);

            assertThatCode(() -> rebuilder.markRebuilding(CACHE))
                    .doesNotThrowAnyException();
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
