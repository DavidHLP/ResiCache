package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.integration.AbstractRedisIntegrationTest;
import io.github.davidhlp.spring.cache.redis.integration.TestApplication;
import io.github.davidhlp.spring.cache.redis.integration.TestRedisConfiguration;
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
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redis 断连故障注入契约。
 *
 * <p>PUT 与 CLEAN 必须抛出 {@link CacheOperationException} 并保留原始原因。
 * GET 仍以 graceful miss 完成，但内部 {@link io.github.davidhlp.spring.cache.redis.chain.CacheResult}
 * 保留 failure status、failure kind 和 cause；Writer 对外返回 {@code null}。
 *
 * <p>测试配置只替换 RedisConnectionFactory；生产自动配置不依赖
 * {@link Primary} 作为用户 Bean 覆盖机制。
 */
@SpringBootTest(classes = {TestApplication.class, RedisDownFaultInjectionIntegrationTest.BrokenRedisConfig.class})
@ActiveProfiles({"integration-test", "redis-down-test"})
@Import(TestRedisConfiguration.class)
@DisplayName("Redis 断连故障注入(GET 路径最小切片)")
class RedisDownFaultInjectionIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired
    private RedisProCacheWriter writer;


    @Test
    @DisplayName("RedisDown-2: PUT fails fast with original cause")
    void redisDown_put_failsFast() {
        assertThatThrownBy(() -> writer.put(
                "testCache",
                "fault-injection-put-key".getBytes(),
                "\"fault-injection-put-value\"".getBytes(),
                null))
                .isInstanceOf(CacheOperationException.class)
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("RedisDown-3: CLEAN fails fast and remains observable")
    void redisDown_clean_failsFast() {
        assertThatThrownBy(() -> writer.clean(
                "testCache", "fault-injection-clean-pattern".getBytes()))
                .isInstanceOf(CacheOperationException.class)
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("RedisDown-1: GET completes as a graceful miss")
    void redisDown_get_degradesGracefully() throws Exception {
        byte[] result = writer.retrieve(
                "testCache", "fault-injection-key".getBytes()).get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(result).isNull();
    }

    /**
     * 故障注入测试用 Redis 不可达配置。
     * <p>用 {@code @Primary} 覆盖 {@link RedisConnectionFactory} bean — 启动时
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
