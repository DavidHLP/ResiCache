package io.github.davidhlp.spring.cache.redis.cache;




import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
@SpringBootTest(
        classes = {TestApplication.class, RedisDownFaultInjectionIntegrationTest.BrokenRedisConfig.class},
        properties = "resi-cache.metrics.enabled=true")
@ActiveProfiles({"integration-test", "redis-down-test"})
@Import(TestRedisConfiguration.class)
@DisplayName("Redis 断连故障注入(GET 路径最小切片)")
class RedisDownFaultInjectionIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired
    private RedisProCacheWriter writer;

    @Autowired
    private org.springframework.cache.CacheManager cacheManager;

    @Autowired
    private SimpleMeterRegistry meterRegistry;

    private double putFailureCount() {
        var counter = meterRegistry.find(CacheFailureReporter.METRIC_NAME)
                .tag("operation", "PUT")
                .tag("kind", "REDIS")
                .tag("strategy", "FAIL_FAST")
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("RedisDown-6: user-level Cache.get(key, loader) returns loader value on write-back failure")
    void redisDown_userLevelGetLoader_loaderValueSurvives() throws Exception {
        // 用户级 read-through API:cacheManager.getCache(name).get(key, loader)。
        // Redis-down:缓存读 graceful miss → loader(业务数据源)成功 → 写回失败。
        // availability-first:必须返回 loader 值。
        org.springframework.cache.Cache cache = cacheManager.getCache("testCache");
        assertThat(cache).isNotNull();

        double beforePutFailure = putFailureCount();
        String value = cache.get("user-level-loader-key", () -> "business-value");

        assertThat(value)
                .as("用户级 read-through:loader 成功值必须穿透 Redis-down 写回失败返回")
                .isEqualTo("business-value");
        assertThat(putFailureCount() - beforePutFailure)
                .as("cache read-through 的 PUT chain failure 只能在 CacheErrorHandler 计数一次")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("RedisDown-7: user-level loader failure surfaces as ValueRetrievalException")
    void redisDown_userLevelLoaderFailure_surfaces() {
        org.springframework.cache.Cache cache = cacheManager.getCache("testCache");
        assertThat(cache).isNotNull();

        assertThatThrownBy(() -> cache.get("user-level-loader-fail-key", () -> {
            throw new IllegalStateException("business loader failed");
        }))
                .as("loader 失败是用户可见失败,包装为 Spring ValueRetrievalException,不得吞为 null")
                .isInstanceOf(org.springframework.cache.Cache.ValueRetrievalException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }


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
        assertThatThrownBy(() -> writer.clear(
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

    @Test
    @DisplayName("RedisDown-4: writer-level read-through keeps the loaded bytes on write-back failure")
    void redisDown_writerReadThrough_loaderValueSurvivesWriteBackFailure() {
        // writer 级入口(getNativeCache() 可达):缓存读 miss → loader 成功 → 写回失败。
        // availability-first:必须返回 loader 值,不得被写回失败覆盖。
        double beforePutFailure = putFailureCount();
        byte[] result = writer.get(
                "testCache",
                "fault-injection-loader-key".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                () -> "\"loaded-data\"".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                null,
                false);

        assertThat(result).isNotNull();
        assertThat(new String(result, java.nio.charset.StandardCharsets.UTF_8))
                .as("loader 成功值必须穿透写回失败返回")
                .isEqualTo("\"loaded-data\"");
        assertThat(putFailureCount() - beforePutFailure)
                .as("native writer read-through 的 PUT chain failure 只能在 CacheErrorHandler 计数一次")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("RedisDown-5: writer-level loader failure still surfaces")
    void redisDown_writerLoaderFailure_stillSurfaces() {
        IllegalStateException loaderBoom = new IllegalStateException("business loader failed");
        assertThatThrownBy(() -> writer.get(
                "testCache",
                "fault-injection-loader-fail-key".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                () -> {
                    throw loaderBoom;
                },
                null,
                false))
                .as("loader 失败是用户可见失败,不得被吞或降级为 null")
                .isSameAs(loaderBoom);
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

        @Bean
        @Primary
        public SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
