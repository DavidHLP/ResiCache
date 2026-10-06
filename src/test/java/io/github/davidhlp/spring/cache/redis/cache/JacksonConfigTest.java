package io.github.davidhlp.spring.cache.redis.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(MapperAutoConfiguration.class));

    @Test
    void providesDedicatedFallback() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ObjectMapper.class);
            assertThat(context).hasBean("resiCacheObjectMapper");
        });
    }

    @Test
    void hostMapperWinsWithoutBeanOverriding() {
        runner.withUserConfiguration(HostConfig.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ObjectMapper.class);
            assertThat(context).doesNotHaveBean("resiCacheObjectMapper");
            assertThat(context.getBean(ObjectMapper.class)).isSameAs(context.getBean("objectMapper"));
        });
    }

    @Test
    void differentlyNamedHostMapperAlsoWins() {
        runner.withUserConfiguration(NamedHostConfig.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ObjectMapper.class);
            assertThat(context).doesNotHaveBean("resiCacheObjectMapper");
        });
    }

    @AutoConfiguration
    @Import(JacksonConfig.class)
    static class MapperAutoConfiguration { }

    @Configuration(proxyBeanMethods = false)
    static class HostConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class NamedHostConfig {
        @Bean ObjectMapper hostJsonMapper() { return new ObjectMapper(); }
    }
}
