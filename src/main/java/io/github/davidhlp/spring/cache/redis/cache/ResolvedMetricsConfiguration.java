package io.github.davidhlp.spring.cache.redis.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 统一解析 opt-in metrics 选择，供运行时和迁移 CLI 两条装配边界复用。
 *
 * <p>由两条边界的装配根显式 {@code @Import}({@code RedisProCacheConfiguration} /
 * {@code SerializationMigrationOperatorConfiguration}),故不带组件注解:组件扫描不会重复注册。
 */
class ResolvedMetricsConfiguration {

    @Bean
    @ConditionalOnMissingBean(ResolvedMetrics.class)
    ResolvedMetrics resolvedMetrics(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            Environment environment) {
        return ResolvedMetrics.resolve(meterRegistryProvider, environment);
    }
}
