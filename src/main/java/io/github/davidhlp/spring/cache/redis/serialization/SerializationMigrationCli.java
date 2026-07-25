package io.github.davidhlp.spring.cache.redis.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Standalone operator entry point for serialization migration.
 *
 * <p>Example:
 * <pre>{@code
 * java -cp resicache.jar:app-libs/* \
 *   io.github...SerializationMigrationCli \
 *   --spring.data.redis.host=localhost \
 *   --resi-cache.serializer.migration.phase=SHADOW_READ
 * }</pre>
 */
public final class SerializationMigrationCli {

    private SerializationMigrationCli() {
    }

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                CliConfiguration.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .properties(
                        "spring.main.lazy-initialization=true",
                        "spring.autoconfigure.exclude="
                                + "io.github.davidhlp.spring.cache.redis.config."
                                + "RedisCacheAutoConfiguration")
                .run(args)) {
            SerializationMigrationReport report =
                    context.getBean(SerializationMigrationEngine.class).migrate();
            if (report.failed() > 0) {
                throw new IllegalStateException(
                        "Serialization migration completed with rejected/failed keys: "
                                + report.failed());
            }
        }
    }

    /** Minimal CLI context: Redis connection + migration beans, no cache/AOP runtime. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableConfigurationProperties(RedisProCacheProperties.class)
    @Import({SecureJacksonSerializerFactory.class, SerializationMigrationEngine.class})
    static class CliConfiguration {
        @Bean
        @ConditionalOnMissingBean(ObjectMapper.class)
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
