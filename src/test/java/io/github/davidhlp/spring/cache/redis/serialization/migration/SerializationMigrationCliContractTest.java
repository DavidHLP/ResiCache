package io.github.davidhlp.spring.cache.redis.serialization.migration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SerializationMigrationCliContractTest {

    @Test
    void cliContext_resolvesMetricsChoiceWithUserRegistry() {
        new ApplicationContextRunner()
                .withUserConfiguration(SerializationMigrationCli.CliConfiguration.class)
                .withPropertyValues("resi-cache.metrics.enabled=true")
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // operator 装配根按类点名:迁移 bean 齐备,CLI 上下文才可用
                    assertThat(context)
                            .hasSingleBean(SerializationMigrationCli.SerializationMigrationRunner.class);
                    assertThat(context).hasBean("resolvedMetrics");
                    assertThat(context.getBean("resolvedMetrics"))
                            .hasFieldOrPropertyWithValue(
                                    "meterRegistry", context.getBean(MeterRegistry.class));
                    // 运行时自动配置被按类排除:CLI 上下文不装配缓存/AOP 运行时
                    assertThat(context)
                            .doesNotHaveBean(
                                    io.github.davidhlp.spring.cache.redis.cache.RedisProCacheManager.class);
                });
    }
}
