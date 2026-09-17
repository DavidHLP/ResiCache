package io.github.davidhlp.spring.cache.redis.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;

/**
 * Resolved opt-in metrics choice shared by the normal runtime assembly.
 *
 * @param meterRegistry the enabled registry, or {@code null} when metrics are disabled/unavailable
 */
record ResolvedMetrics(@Nullable MeterRegistry meterRegistry) {

    private static final String METRICS_ENABLED_PROPERTY = "resi-cache.metrics.enabled";

    static ResolvedMetrics resolve(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            @Nullable Environment environment) {
        if (environment != null
                && !environment.getProperty(METRICS_ENABLED_PROPERTY, Boolean.class, false)) {
            return new ResolvedMetrics(null);
        }
        return new ResolvedMetrics(
                meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable());
    }
}
