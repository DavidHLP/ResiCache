package io.github.davidhlp.spring.cache.redis.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import org.springframework.lang.Nullable;

/** Owns metrics registration, recording primitives, and the disabled seam shape. */
final class MetricsWriter {

    private MetricsWriter() { }

    static boolean disabled(@Nullable MeterRegistry registry) {
        return registry == null || DisabledMetricsRegistry.isDisabledSeam(registry);
    }

    @Nullable
    static Counter counter(@Nullable MeterRegistry registry, String name, String description) {
        if (disabled(registry)) {
            return null;
        }
        return Counter.builder(name).description(description).register(registry);
    }

    @Nullable
    static Counter counter(@Nullable MeterRegistry registry, String name, String description, String... tags) {
        if (disabled(registry)) {
            return null;
        }
        return Counter.builder(name).description(description).tags(tags).register(registry);
    }

    @Nullable
    static Counter taggedCounter(@Nullable MeterRegistry registry, String name, String... tags) {
        if (disabled(registry)) {
            return null;
        }
        return Counter.builder(name).tags(tags).register(registry);
    }

    @Nullable
    static Timer timer(@Nullable MeterRegistry registry, String name, String description, String... tags) {
        if (disabled(registry)) {
            return null;
        }
        return Timer.builder(name).description(description).tags(tags).register(registry);
    }

    static <T> void gauge(@Nullable MeterRegistry registry, String name, T state,
                          ToDoubleFunction<T> valueFunction, String description, String... tags) {
        if (disabled(registry)) {
            return;
        }
        Gauge.builder(name, state, valueFunction).description(description).tags(tags).register(registry);
    }

    static void increment(@Nullable Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    static void record(@Nullable Timer timer, long amount, TimeUnit unit) {
        if (timer != null) {
            timer.record(amount, unit);
        }
    }
}
