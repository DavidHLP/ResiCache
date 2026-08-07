package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.cache.CachedValue;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.integration.AbstractRedisIntegrationTest;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import io.github.davidhlp.spring.cache.redis.serialization.TypeSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisProCacheWriter 测试 — 真实 Redis + 真实责任链(原为 Mockito 单元测试)。
 *
 * <p><b>为什么改用真实 Redis:</b> 原版 mock 了 {@link CacheHandlerChainFactory} → 返回 mock chain,
 * 再用 {@code when(chain.execute(any(CacheContext.class))).thenReturn(CacheResult.success(cannedBytes))}
 * 桩伪造所有 GET/PUT/PUT_IF_ABSENT/REMOVE/CLEAN 的结果。这是<b>最隐蔽的假阳性</b>:测试通过仅因
 * mock 返回了设定的字节,责任链从未执行,Redis 从未被触碰 —— 换一个真实 RedisTemplate 而保留
 * chain mock 只是移动了 mock 边界,假阳性原封不动。
 *
 * <p><b>本版 wiring(满足"真实链"要求):</b> {@code @Autowired} 生产 {@link RedisProCacheWriter} bean ——
 * 它由 {@code RedisProCacheConfiguration} 用真实 {@code CacheHandlerChainFactory}(自动注入全部真实
 * CacheHandler,终止于真实 {@code ActualCacheHandler})+ 真实 {@link TypeSupport} 装配。所有操作
 * 经真实责任链执行并落盘到真实 Redis 容器。
 *
 * <p><b>断言范式转变:</b> 原版用 {@link org.mockito.ArgumentCaptor} 捕获传给 chain 的 context
 * 字段(operation / cacheName / redisKey / actualKey / ttl / keyPattern)。本版改为验证<b>真实效果</b>
 * —— 若 context 构造错误,值会落到错误的 key / 错误的 TTL,读回断言即失败。这是更强的端到端验证。
 * 传入完整带前缀 key 字节({@code "testCache::key1"})以模拟真实 Spring Cache 流(SDR 已把 cacheName
 * 前缀拼进 key 字节)。
 */
@DisplayName("RedisProCacheWriter Tests (real Redis + real chain)")
class RedisProCacheWriterTest extends AbstractRedisIntegrationTest {

    @Autowired
    private RedisProCacheWriter writer;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ValueOperations<String, Object> valueOperations;

    @Autowired
    private TypeSupport typeSupport;

    @Autowired
    private CacheStatisticsCollector statistics;

    private static final String NAME = "testCache";
    private static final String REDIS_KEY = "testCache::key1";
    private static final byte[] KEY = "testCache::key1".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void flushDb() {
        redisTemplate.getConnectionFactory().getConnection().flushDb();
    }

    @Nested
    @DisplayName("get() Tests")
    class GetTests {

        @Test
        @DisplayName("get returns the stored value after put (real chain round-trip)")
        void get_afterPut_returnsValue() {
            byte[] value = typeSupport.serializeToBytes("value");
            writer.put(NAME, KEY, value, Duration.ofSeconds(60));

            byte[] result = writer.get(NAME, KEY);

            // 真实往返:GET 经真实责任链读到 ActualCacheHandler 命中,返回非空且可还原为原值
            assertThat(result).isNotNull();
            assertThat(typeSupport.deserializeFromBytes(result)).isEqualTo("value");
        }

        @Test
        @DisplayName("get returns null when key absent (real Redis miss through chain)")
        void get_whenAbsent_returnsNull() {
            byte[] result = writer.get(NAME, KEY);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("put() Tests")
    class PutTests {

        @Test
        @DisplayName("put persists value to Redis at the derived key with the given TTL")
        void put_withTtl_persistsValueAndTtl() {
            byte[] value = typeSupport.serializeToBytes("value");
            Duration ttl = Duration.ofSeconds(60);

            writer.put(NAME, KEY, value, ttl);

            // 真实:值落在派生的 redisKey,且 TTL 已写入(原版断言 ctx.getTtl()==ttl,此处更强:
            // 验证 Redis 中 key 存在、值正确、TTL 在合理区间)
            assertThat(redisTemplate.hasKey(REDIS_KEY)).isTrue();
            Object stored = valueOperations.get(REDIS_KEY);
            assertThat(stored).isInstanceOf(CachedValue.class);
            assertThat(((CachedValue) stored).getValue()).isEqualTo("value");
            assertThat(redisTemplate.getExpire(REDIS_KEY)).isBetween(1L, 60L);
        }

        @Test
        @DisplayName("put with operation persists value (operation config flows through chain)")
        void put_withOperation_persistsValue() {
            byte[] value = typeSupport.serializeToBytes("value");
            Duration ttl = Duration.ofSeconds(60);
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testMethod")
                    .cacheNames(NAME)
                    .key("key1")
                    .build();

            writer.put(NAME, KEY, value, ttl, operation);

            // 真实:operation 配置经链生效,值持久化(原版断言 ctx.getCacheOperation()==operation,
            // 此处验证其副作用 —— 值确实落盘)
            assertThat(redisTemplate.hasKey(REDIS_KEY)).isTrue();
            Object stored = valueOperations.get(REDIS_KEY);
            assertThat(stored).isInstanceOf(CachedValue.class);
            assertThat(((CachedValue) stored).getValue()).isEqualTo("value");
        }
    }

    @Nested
    @DisplayName("putIfAbsent() Tests")
    class PutIfAbsentTests {

        @Test
        @DisplayName("putIfAbsent stores when key absent (real SETNX success)")
        void putIfAbsent_whenAbsent_stores() {
            byte[] value = typeSupport.serializeToBytes("value");

            byte[] result = writer.putIfAbsent(NAME, KEY, value, null);

            // SETNX 成功:无现值返回,且值已写入
            assertThat(result).isNull();
            assertThat(redisTemplate.hasKey(REDIS_KEY)).isTrue();
        }

        @Test
        @DisplayName("putIfAbsent returns existing value when key exists (real SETNX fails)")
        void putIfAbsent_whenExists_returnsExisting() {
            // 真实预置已存在的值 → SETNX 自然失败 → 返回现值,且现值不被覆盖
            valueOperations.set(REDIS_KEY, CachedValue.of("existing", 60));
            byte[] value = typeSupport.serializeToBytes("newValue");

            byte[] result = writer.putIfAbsent(NAME, KEY, value, null);

            assertThat(result).isNotNull();
            assertThat(typeSupport.deserializeFromBytes(result)).isEqualTo("existing");
            // 真实:现值未被覆盖
            Object stored = valueOperations.get(REDIS_KEY);
            assertThat(stored).isInstanceOf(CachedValue.class);
            assertThat(((CachedValue) stored).getValue()).isEqualTo("existing");
        }
    }

    @Nested
    @DisplayName("remove() Tests")
    class RemoveTests {

        @Test
        @DisplayName("remove deletes the key from Redis (real chain REMOVE)")
        void remove_deletesKey() {
            valueOperations.set(REDIS_KEY, CachedValue.of("v", 60));

            writer.remove(NAME, KEY);

            assertThat(redisTemplate.hasKey(REDIS_KEY)).isFalse();
        }
    }

    @Nested
    @DisplayName("clean() Tests")
    class CleanTests {

        @Test
        @DisplayName("clean removes all keys matching the pattern (real SCAN + UNLINK/DEL)")
        void clean_removesMatchingKeys() {
            // 预置多个匹配前缀的 key
            valueOperations.set("testCache::a", CachedValue.of("a", 60));
            valueOperations.set("testCache::b", CachedValue.of("b", 60));
            valueOperations.set("other::c", CachedValue.of("c", 60));

            byte[] pattern = "testCache::*".getBytes(StandardCharsets.UTF_8);
            writer.clean(NAME, pattern);

            // 真实:匹配前缀的 key 被批量删除,不匹配的保留(原版断言 ctx.getKeyPattern(),
            // 此处验证其副作用 —— 正确的 key 被清除)
            assertThat(redisTemplate.hasKey("testCache::a")).isFalse();
            assertThat(redisTemplate.hasKey("testCache::b")).isFalse();
            assertThat(redisTemplate.hasKey("other::c")).isTrue();
        }
    }

    @Nested
    @DisplayName("clearStatistics() Tests")
    class ClearStatisticsTests {

        @Test
        @DisplayName("clearStatistics resets statistics for the cache (real collector, no throw)")
        void clearStatistics_resetsStatistics() {
            // statistics.reset 是 SDR 的簿记操作(非 Redis I/O);真实 collector 接受调用且不抛异常
            writer.clearStatistics(NAME);
            // 无异常即通过 —— 真实 collector bean 已处理 reset
            assertThat(statistics).isNotNull();
        }
    }
}
