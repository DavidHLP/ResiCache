package io.github.davidhlp.spring.cache.redis.config;

import io.github.davidhlp.spring.cache.redis.observability.RedisCacheHealthIndicator;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import org.springframework.data.redis.core.RedisTemplate;

/**
 * Metrics auto-configuration for Redis cache monitoring.
 *
 * <p>WS-1.4 评估结论(2026-06-29)— <strong>默认 OFF,opt-in 开启</strong>。
 * 决策: 作为 library,observability 行为必须显式 opt-in —— 默认开启会:
 * <ol>
 *   <li>把 {@code MeterRegistry}/{@code HealthIndicator} 变成强依赖(用户集成
 *       Spring Boot Actuator 时 surprise)</li>
 *   <li>运行时增加非零开销(timer/counter sample),违背\"最小惊讶\"原则</li>
 *   <li>和下游用户的 observability 栈(Prometheus/OTEL)耦合,可能冲突</li>
 * </ol>
 *
 * <p>提供 a health indicator for Spring Boot Actuator. Metrics recording is
 * handled inline by {@code RedisProCache} via the {@link MeterRegistry} directly,
 * so no separate metrics-recording bean is registered here. Metrics/health are
 * disabled by default and must be explicitly enabled via
 * {@code resi-cache.metrics.enabled=true}.
 *
 * <p>This configuration is only loaded when:
 * <ul>
 *   <li>Micrometer's {@link MeterRegistry} is on the classpath</li>
 *   <li>{@code resi-cache.metrics.enabled} is set to {@code true}</li>
 * </ul>
 *
 * <p>启用示例(application.yml):
 * <pre>{@code
 * resi-cache:
 *   metrics:
 *     enabled: true   # 显式开启 — 注册 RedisCacheHealthIndicator + 启用 Timer/Counter
 * }</pre>
 *
 * <p>The health indicator can be overridden by the user via a custom {@link Bean} definition.
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass({MeterRegistry.class, HealthIndicator.class})
@ConditionalOnProperty(prefix = "resi-cache.metrics", name = "enabled", havingValue = "true")
public class MetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RedisCacheHealthIndicator redisCacheHealthIndicator(
            RedisTemplate<String, Object> redisTemplate,
            ObjectProvider<io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncSupport> syncSupportProvider,
            ObjectProvider<RedisProCacheProperties> propertiesProvider) {
        return new RedisCacheHealthIndicator(redisTemplate, syncSupportProvider, propertiesProvider);
    }
}
