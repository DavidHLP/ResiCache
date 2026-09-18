package io.github.davidhlp.spring.cache.redis.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 统一解析 opt-in metrics 选择，供运行时和迁移 CLI 两条装配边界复用。
 */
@Configuration(proxyBeanMethods = false)
class ResolvedMetricsConfiguration {

    @Bean
    @ConditionalOnMissingBean(ResolvedMetrics.class)
    ResolvedMetrics resolvedMetrics(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            Environment environment) {
        return ResolvedMetrics.resolve(meterRegistryProvider, environment);
    }
}
