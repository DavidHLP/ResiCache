package io.github.davidhlp.spring.cache.redis.config;

import io.github.davidhlp.spring.cache.redis.actuator.RedisCacheHealthIndicator;
import io.github.davidhlp.spring.cache.redis.core.metrics.CacheMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.Optional;

import org.springframework.data.redis.core.RedisTemplate;

/**
 * Metrics auto-configuration for Redis cache monitoring.
 *
 * <p>Provides cache metrics recording via Micrometer and health indicators
 * for Spring Boot Actuator. Metrics are disabled by default and must be
 * explicitly enabled via {@code resi-cache.metrics.enabled=true}.
 *
 * <p>This configuration is only loaded when:
 * <ul>
 *   <li>Micrometer's {@link MeterRegistry} is on the classpath</li>
 *   <li>{@code resi-cache.metrics.enabled} is set to {@code true}</li>
 * </ul>
 *
 * <p>Both beans can be overridden by the user via custom {@link Bean} definitions.
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass({MeterRegistry.class, HealthIndicator.class})
@ConditionalOnProperty(prefix = "resi-cache.metrics", name = "enabled", havingValue = "true")
public class MetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CacheMetricsRecorder cacheMetricsRecorder(MeterRegistry meterRegistry) {
        return new CacheMetricsRecorder(Optional.of(meterRegistry));
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisCacheHealthIndicator redisCacheHealthIndicator(RedisTemplate<String, Object> redisTemplate) {
        return new RedisCacheHealthIndicator(redisTemplate);
    }
}
