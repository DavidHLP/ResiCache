package io.github.davidhlp.spring.cache.redis.serialization.migration;



import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.cache.SerializationMigrationOperatorConfiguration;
import io.github.davidhlp.spring.cache.redis.config.RedisCacheAutoConfiguration;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                .properties("spring.main.lazy-initialization=true")
                .run(args)) {
            SerializationMigrationReport report = context
                    .getBean(SerializationMigrationRunner.class).migrate();
            if (report.failed() > 0) {
                throw new IllegalStateException(
                        "Serialization migration completed with rejected/failed keys: "
                                + report.failed());
            }
        }
    }

    /**
     * Operator 边界装配根：Redis 连接 + 迁移 bean,按类点名,不做包扫描;
     * 运行时自动配置按类排除,CLI 上下文因此不会装配缓存/AOP 运行时。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = RedisCacheAutoConfiguration.class)
    @EnableConfigurationProperties(RedisProCacheProperties.class)
    @Import(SerializationMigrationOperatorConfiguration.class)
    static class CliConfiguration {
        @Bean
        @ConditionalOnMissingBean(ObjectMapper.class)
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    /** Stable operator seam implemented by the internal migration engine. */
    public interface SerializationMigrationRunner {
        SerializationMigrationReport migrate();
    }
}
