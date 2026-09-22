package io.github.davidhlp.spring.cache.redis.cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;

/**
 * Single owner of the "are metrics enabled" decision: the opt-in key is read here,
 * once, so assembly and tests cross the same check.
 *
 * <p>Callers always get a non-null seam. When metrics are disabled, or the application has
 * no {@code MeterRegistry} bean, the seam is the shared no-op adapter
 * {@link #NOOP_REGISTRY} — a {@link CompositeMeterRegistry} that never gets a child
 * registry, so every recorded sample lands nowhere. The disabled case is therefore not a
 * {@code registry == null} re-check at each caller.
 *
 * @param meterRegistry metrics seam, never {@code null}
 */
record ResolvedMetrics(MeterRegistry meterRegistry) {

    /** No-op metrics seam used when metrics are disabled or unavailable. */
    static final MeterRegistry NOOP_REGISTRY = new CompositeMeterRegistry();

    private static final String METRICS_ENABLED_PROPERTY = "resi-cache.metrics.enabled";

    static ResolvedMetrics resolve(
            @Nullable ObjectProvider<MeterRegistry> meterRegistryProvider,
            Environment environment) {
        MeterRegistry registry =
                meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        boolean enabled = environment.getProperty(METRICS_ENABLED_PROPERTY, Boolean.class, false);
        return new ResolvedMetrics(enabled && registry != null ? registry : NOOP_REGISTRY);
    }
}
