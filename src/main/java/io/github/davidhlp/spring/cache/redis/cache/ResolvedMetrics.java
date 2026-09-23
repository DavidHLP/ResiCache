package io.github.davidhlp.spring.cache.redis.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;

/**
 * Single owner of the "are metrics enabled" decision: the opt-in key is read here,
 * once, so assembly and tests cross the same check.
 *
 * <p>Callers always get a non-null seam. When metrics are disabled, or the application has
 * no {@code MeterRegistry} bean, the seam is the single shared
 * {@link DisabledMetricsRegistry#INSTANCE} — a stateless sink that registers nothing and
 * retains nothing. The disabled case is therefore not a {@code registry == null} re-check
 * at each caller, and it cannot accumulate meters either.
 *
 * <p>The opt-in is read <em>before</em> the provider is touched: with metrics disabled the
 * application's registry beans are neither resolved nor instantiated, so an ambiguous
 * {@code MeterRegistry} set cannot fail an assembly that explicitly turned metrics off.
 *
 * @param meterRegistry metrics seam, never {@code null}
 */
record ResolvedMetrics(MeterRegistry meterRegistry) {

    private static final String METRICS_ENABLED_PROPERTY = "resi-cache.metrics.enabled";

    static ResolvedMetrics resolve(
            @Nullable ObjectProvider<MeterRegistry> meterRegistryProvider,
            Environment environment) {
        if (!environment.getProperty(METRICS_ENABLED_PROPERTY, Boolean.class, false)) {
            return new ResolvedMetrics(noOpAdapter());
        }
        MeterRegistry registry =
                meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        return new ResolvedMetrics(registry != null ? registry : noOpAdapter());
    }

    /**
     * Nothing-publishing metrics seam: the one shared, stateless
     * {@link DisabledMetricsRegistry#INSTANCE}.
     *
     * <p>Sharing is safe because the sink holds no state — no child registries and no meter
     * map — so every application context that turns metrics off (or that has no
     * {@code MeterRegistry} bean) can use the same instance without leaking meter ids or
     * tag strings between contexts, and without growing a map for the lifetime of a
     * dynamically named cache.
     */
    private static MeterRegistry noOpAdapter() {
        return DisabledMetricsRegistry.INSTANCE;
    }
}
