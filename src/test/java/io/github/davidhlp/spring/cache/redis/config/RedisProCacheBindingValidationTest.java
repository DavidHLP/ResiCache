package io.github.davidhlp.spring.cache.redis.config;




import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置绑定期校验测试(P1-CONFIG-001)。
 *
 * <p>用 {@link ApplicationContextRunner} + {@link EnableConfigurationProperties}
 * 走 Spring Boot 真实绑定路径:非法配置在<b>绑定完成时</b>上下文启动失败
 * (ConfigurationPropertiesBindingPostProcessor 施加 @Validated 校验),
 * 失败信息含完整属性路径;默认配置启动通过。
 */
@DisplayName("Config Binding Validation Tests")
class RedisProCacheBindingValidationTest {

    @Configuration
    @EnableConfigurationProperties(RedisProCacheProperties.class)
    static class PropsConfig {
    }

    private static ApplicationContextRunner runner(String... props) {
        return new ApplicationContextRunner()
                .withUserConfiguration(PropsConfig.class)
                .withPropertyValues(props);
    }

    /** 拼接 startup failure 全 cause 链消息,供路径断言。 */
    private static String fullFailureMessage(org.springframework.boot.test.context.assertj.AssertableApplicationContext ctx) {
        StringBuilder sb = new StringBuilder();
        Throwable t = ctx.getStartupFailure();
        while (t != null) {
            sb.append(t.getMessage()).append('\n');
            t = t.getCause();
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("默认配置通过")
    class DefaultsBootTests {

        @Test
        @DisplayName("默认属性启动通过(无 violation)")
        void defaults_bootClean() {
            runner().run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("显式合法值(bloom/redisson 边界)启动通过")
        void explicitValidValues_bootClean() {
            runner(
                    "resi-cache.bloom.bit-size=1024",
                    "resi-cache.bloom.hash-functions=5",
                    "resi-cache.redis.mode=single",
                    "resi-cache.redis.port=6380")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("字段约束违规 → 绑定期启动失败(含属性路径)")
    class FieldConstraintTests {

        @Test
        @DisplayName("bloom.bit-size=0 → 失败,消息含 bitSize 路径")
        void bloomBitSize_zero_failsWithPropertyPath() {
            runner("resi-cache.bloom.bit-size=0").run(context -> {
                assertThat(context).hasFailed();
                assertThat(fullFailureMessage(context))
                        .contains("bitSize");
            });
        }

        @Test
        @DisplayName("bloom.hash-functions=0 → 失败")
        void bloomHashFunctions_zero_fails() {
            runner("resi-cache.bloom.hash-functions=0").run(context -> {
                assertThat(context).hasFailed();
            });
        }

        @Test
        @DisplayName("redisson.connection-pool-size=0 → 失败,消息含 connectionPoolSize")
        void redissonPoolSize_zero_fails() {
            runner("resi-cache.redisson.connection-pool-size=0").run(context -> {
                assertThat(context).hasFailed();
                assertThat(fullFailureMessage(context))
                        .contains("connectionPoolSize");
            });
        }

        @Test
        @DisplayName("early-expiration.pool-size=0 → 失败")
        void earlyExpirationPoolSize_zero_fails() {
            runner("resi-cache.early-expiration.pool-size=0").run(context -> {
                assertThat(context).hasFailed();
            });
        }

        @Test
        @DisplayName("redis.port=70000 → 失败(超出 1-65535)")
        void redisPort_outOfRange_fails() {
            runner("resi-cache.redis.port=70000").run(context -> {
                assertThat(context).hasFailed();
                assertThat(fullFailureMessage(context))
                        .contains("port");
            });
        }
    }

    @Nested
    @DisplayName("跨字段约束违规 → 绑定期启动失败(含属性路径)")
    class CrossFieldConstraintTests {

        @Test
        @DisplayName("redis.mode=cluster 但无 cluster-nodes → 失败,消息含 clusterNodes")
        void clusterMode_withoutNodes_fails() {
            runner("resi-cache.redis.mode=cluster").run(context -> {
                assertThat(context).hasFailed();
                assertThat(fullFailureMessage(context))
                        .contains("clusterNodes");
            });
        }

        @Test
        @DisplayName("redis.mode=sentinel 但无 sentinel-master → 失败,消息含 sentinelMaster")
        void sentinelMode_withoutConfig_fails() {
            runner("resi-cache.redis.mode=sentinel").run(context -> {
                assertThat(context).hasFailed();
                assertThat(fullFailureMessage(context))
                        .contains("sentinelMaster");
            });
        }

        @Test
        @DisplayName("redis.mode=未知值 → 失败,消息含 mode")
        void unknownMode_fails() {
            runner("resi-cache.redis.mode=bogus").run(context -> {
                assertThat(context).hasFailed();
                assertThat(fullFailureMessage(context))
                        .contains("mode");
            });
        }

        @Test
        @DisplayName("tls-required=true 但 tls-enabled=false → 失败,消息含 tlsEnabled")
        void tlsRequired_withoutEnabled_fails() {
            runner(
                    "resi-cache.redis.tls-required=true",
                    "resi-cache.redis.tls-enabled=false").run(context -> {
                assertThat(context).hasFailed();
                assertThat(fullFailureMessage(context))
                        .contains("tlsEnabled");
            });
        }
    }
}
