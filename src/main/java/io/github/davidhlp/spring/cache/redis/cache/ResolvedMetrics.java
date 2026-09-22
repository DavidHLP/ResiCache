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
 * no {@code MeterRegistry} bean, the seam is a {@link CompositeMeterRegistry} that never
 * gets a child registry, so every recorded sample lands nowhere. The disabled case is
 * therefore not a {@code registry == null} re-check at each caller.
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
     * Nothing-publishing metrics seam, created per resolution.
     *
     * <p>Deliberately not a shared instance: a {@link CompositeMeterRegistry} keeps every
     * meter it is asked for in its own map, so one static sink would accumulate the meter
     * ids and tag strings of every application context — and of every dynamically named
     * cache — for the lifetime of the JVM. One adapter per resolution keeps that bounded by
     * the context that owns the seam.
     *
     * <p>{@code ponytail}: meters registered into this adapter are still allocated until the
     * owning context closes. A zero-allocation seam would need a hand-written
     * non-registering {@code MeterRegistry}; add one only if a long-lived context is
     * observed accumulating disabled-path meters.
     */
    private static MeterRegistry noOpAdapter() {
        return new CompositeMeterRegistry();
    }
}
