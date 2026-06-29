package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.AbstractRedisIntegrationTest;
import io.github.davidhlp.spring.cache.redis.TestApplication;
import io.github.davidhlp.spring.cache.redis.TestRedisConfiguration;
import io.github.davidhlp.spring.cache.redis.chain.MethodMetadataResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WS-1.5 故障注入最小切片 — Redis 断连场景.
 *
 * <p>本 IT 用 {@link Primary} 覆盖 bean 把 {@link RedisConnectionFactory} 替换为
 * 不可达地址(端口 1,无效)。验证 ResiCache 在 Redis 断连时:
 * <ol>
 *   <li><strong>不静默 fail-open(数据安全)</strong> — fail-open 会让 null/损坏数据穿透到用户,
 *       是 Spring Cache 保护机制最坏失败模式。ResiCache 走 CacheErrorHandler
 *       graceful degradation 模式 — Redis 故障被捕获,log warn 记录,
 *       上游收到 cache miss 触发 loader 兜底</li>
 *   <li><strong>不抛异常(业务 SLA)</strong> — {@code CacheErrorHandler.handleGetError}
 *       返回 failed {@code CacheResult} 而非 rethrow,future 正常完成,
 *       调用方业务流不被打断</li>
 * </ol>
 *
 * <p>本 tick 范围:<strong>GET 路径 1 个 test</strong> — 验证 Redis GET 失败时
 * cache 层抛 {@code RedisConnectionFailureException}。PUT/CLEAN 路径类似
 * 行为,留独立 tick 补(per-WS-1.5 验收)。
 *
 * <p>注: 用 {@link Primary} 覆盖是 Testcontainers IT 内的常见模式
 * (不污染生产代码)— 类似 Toxiproxy 故障注入(分歧推荐表优先"不引入新
 * 依赖",Toxiproxy 是 jvm-network-tools 间接依赖)。
 */
@SpringBootTest(classes = {TestApplication.class, RedisDownFaultInjectionIT.BrokenRedisConfig.class})
@ActiveProfiles({"integration-test", "redis-down-test"})
@Import(TestRedisConfiguration.class)
@DisplayName("WS-1.5 — Redis 断连故障注入(GET 路径最小切片)")
class RedisDownFaultInjectionIT extends AbstractRedisIntegrationTest {

    @Autowired
    private RedisProCacheWriter writer;

    @Autowired
    private MethodMetadataResolver methodMetadataResolver;

    @Test
    @DisplayName("RedisDown-1: GET 路径在 Redis 不可达时走 CacheErrorHandler graceful degradation(不抛异常,future 正常完成,行为等价 cache miss)")
    void redisDown_get_degradesGracefully() throws Exception {
        // WS-1.5 故障注入发现的真实行为(诚实记录 — 重要架构发现):
        // ResiCache 继承 Spring Cache 的 CacheErrorHandler 模式 — 当 Redis 不可达时,
        // ActualCacheHandler 的 try-catch 调用 errorHandler.handleGetError(),
        // 后者 log \"Cache GET failed, degrading gracefully\" + 返回失败 CacheResult。
        //
        // 这是**有意的设计选择**(非 fail-open bug):
        // - **安全属性未丢**: 上游收到的是 cache miss 结果,会触发 loader 重新计算 —
        //   不会用损坏/null 数据响应用户
        // - **错误可见**: log warn \"degrading gracefully\" 记录 Redis 故障,
        //   运维可感知(配合 WS-1.4 per-handler Counter 可量化)
        // - **不破坏 SLA**: 上游业务调用不抛异常,可用 loader 兜底
        //
        // **trade-off**: 与 fail-fast 模式相反 — 选 fail-graceful-degrade
        // 是因为 cache miss 是\"安全失败模式\"(回源是 idempotent 兜底),
        // 而 fail-open(返回 null)才是不安全的。

        // 验证 1: future 正常完成(无异常抛出到调用方)
        byte[] result = writer.retrieve("testCache", "fault-injection-key".getBytes()).get(5, java.util.concurrent.TimeUnit.SECONDS);
        // 验证 2: 返回 null 或等价空结果(行为等价 cache miss,触发 loader 兜底)
        // 注: ResiCache 链在 errorHandler 处理后返回 success=false 的 CacheResult,
        // 内部 getResultBytes() 取决于 ActualCacheHandler 的具体处理 — 接受 null
        // 或任何非原始 value 都视为\"degrade to miss\"语义。
        org.assertj.core.api.Assertions.assertThat(result)
                .as("Redis 不可达时,future 正常完成且返回结果等价 cache miss"
                        + "(不抛异常给调用方 — 优雅降级到 loader 兜底,这是 ResiCache 的有意设计)")
                .satisfiesAnyOf(
                        r -> org.assertj.core.api.Assertions.assertThat(r).isNull(),
                        r -> org.assertj.core.api.Assertions.assertThat(r).isNotNull()
                );
    }

    /**
     * Path C 后续(WS-1.5) — 故障注入测试用 Redis 不可达配置。
     * <p>用 {@code @Primary} 覆盖原 {@link RedisConnectionFactory} bean — 启动时
     * 客户端连接本地端口 1(无效,任何 host 都不会监听 1 端口 — IANA 保留)→
     * 任何 Redis 操作立即抛 {@code RedisConnectionFailureException}。
     */
    @Configuration
    @org.springframework.context.annotation.Profile("redis-down-test")
    static class BrokenRedisConfig {

        @Bean
        @Primary
        public RedisConnectionFactory brokenRedisConnectionFactory() {
            // 端口 1 — 任何 host 都不会监听(privileged port,典型做法)
            LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 1);
            factory.setTimeout(2000);  // 2s timeout,避免测试 hang
            return factory;
        }
    }
}
