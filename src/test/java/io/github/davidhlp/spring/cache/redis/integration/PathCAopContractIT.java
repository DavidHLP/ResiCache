package io.github.davidhlp.spring.cache.redis.integration;

import io.github.davidhlp.spring.cache.redis.AbstractRedisIntegrationTest;
import io.github.davidhlp.spring.cache.redis.TestApplication;
import io.github.davidhlp.spring.cache.redis.TestRedisConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Path C Step 0 — AOP 行为回归契约测试.
 *
 * <p>在 WS-1.3 Path C 7 步重构(销毁 ThreadLocal)启动前,固化当前 AOP 行为契约:
 * <ul>
 *   <li>纯 Spring {@code @Cacheable} 通过 ResiCache 缓存链路正常工作 ——
 *       保护 Step 3 {@code ResiCacheMethodInterceptor} 不破坏 Spring 原生注解处理</li>
 *   <li>{@code @RedisCacheable} + {@code useBloomFilter} 触发布隆处理器</li>
 *   <li>{@code @RedisCacheable} + {@code sync} 触发同步锁处理器</li>
 *   <li>{@code @RedisCacheable} + {@code ttl} 触发 TTL 处理器,Redis 实际 TTL 匹配</li>
 * </ul>
 *
 * <p>Step 1 起每改一处,本测试须仍全绿(零回归护栏)。
 * 详见 {@code TASK_BACKLOG.md} §2 #1 + {@code MASTER_PLAN.md} §6 Path C 定义。
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Import(TestRedisConfiguration.class)
@DisplayName("Path C Step 0 — AOP 行为回归契约")
class PathCAopContractIT extends AbstractRedisIntegrationTest {

    @Autowired
    private TestCacheService cacheService;

    @Autowired
    private RedisTemplate<String, Object> redisCacheTemplate;

    @BeforeEach
    void setUp() {
        redisCacheTemplate.getConnectionFactory().getConnection().flushDb();
        cacheService.reset();
    }

    @Nested
    @DisplayName("纯 Spring @Cacheable 经 ResiCache 链路")
    class PureSpringCacheableTests {

        @Test
        @DisplayName("PathC-Step0-1: 纯 @Cacheable 走 ResiCache 链,二次调用不重入方法")
        void pureCacheable_cachesThroughResiCacheChain() {
            String r1 = cacheService.getByIdWithPureSpring(1L);
            String r2 = cacheService.getByIdWithPureSpring(1L);
            assertThat(r1).isEqualTo("pure-1");
            assertThat(r2).isEqualTo("pure-1");
            assertThat(cacheService.getCallCount())
                    .as("第二次调用应命中缓存,方法体不重入")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("@RedisCacheable 链处理器契约")
    class ChainHandlerTests {

        @Test
        @DisplayName("PathC-Step0-2: useBloomFilter=true 走链(布隆处理器触发)")
        void bloomFilter_handlerFired() {
            cacheService.getByIdWithBloomFilter(1L);
            cacheService.getByIdWithBloomFilter(1L);
            assertThat(cacheService.getCallCount())
                    .as("BloomFilterHandler + ActualCacheHandler 应保证二次调用命中,方法不重入")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("PathC-Step0-3: sync=true 走链(同步锁处理器触发)")
        void sync_handlerFired() {
            cacheService.getByIdWithSync(1L);
            cacheService.getByIdWithSync(1L);
            assertThat(cacheService.getCallCount())
                    .as("SyncLockHandler + ActualCacheHandler 应保证二次调用命中,方法不重入")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("PathC-Step0-4: ttl=120 走链(TTL 处理器触发),Redis 实际 TTL 匹配")
        void ttl_handlerFiredAndAppliedToRedis() {
            cacheService.getByIdWithTtl(1L);
            Long actualTtl = redisCacheTemplate.getExpire("testCache::1", TimeUnit.SECONDS);
            // @RedisCacheable(ttl=120) 未设 randomTtl,DefaultTtlPolicy.calculateFinalTtl
            // 在 randomTtl=false 分支直接返回 baseTtl=120;allow 1s 漂移防极端时钟。
            assertThat(actualTtl)
                    .as("Redis 实际 TTL 应在 [119, 120] 秒(TtlHandler 未开 randomTtl)")
                    .isBetween(119L, 120L);
        }
    }
}
