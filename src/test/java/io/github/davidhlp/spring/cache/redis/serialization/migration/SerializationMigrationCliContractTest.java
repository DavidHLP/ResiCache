package io.github.davidhlp.spring.cache.redis.serialization.migration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SerializationMigrationCliContractTest {

    @Test
    void cliContextLoadsWithoutRuntimeMetricsChoice() {
        new ApplicationContextRunner()
                .withUserConfiguration(SerializationMigrationCli.CliConfiguration.class)
                .withPropertyValues(
                        "spring.autoconfigure.exclude="
                                + "io.github.davidhlp.spring.cache.redis.config.RedisCacheAutoConfiguration")
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(SerializationMigrationCli.SerializationMigrationRunner.class);
                    assertThat(context).doesNotHaveBean("resolvedMetrics");
                });
    }
}
