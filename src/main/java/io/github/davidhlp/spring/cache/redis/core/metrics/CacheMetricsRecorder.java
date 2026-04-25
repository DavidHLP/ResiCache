package io.github.davidhlp.spring.cache.redis.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records cache metrics including hits, misses, latency, and slow operations.
 * Metrics are disabled by default and must be enabled via configuration.
 */
@Component
public class CacheMetricsRecorder {

    private final Optional<MeterRegistry> meterRegistry;
    private final Map<String, Counter> hitCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> missCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> latencyTimers = new ConcurrentHashMap<>();
    private final Map<String, Counter> slowOperationCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> cacheSizes = new ConcurrentHashMap<>();

    public CacheMetricsRecorder(Optional<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordHit(String cacheName) {
        getHitCounter(cacheName).ifPresent(counter -> counter.increment());
    }

    public void recordMiss(String cacheName) {
        getMissCounter(cacheName).ifPresent(counter -> counter.increment());
    }

    public void recordLatency(String cacheName, long durationMs) {
        getLatencyTimer(cacheName).ifPresent(timer -> timer.record(durationMs, TimeUnit.MILLISECONDS));
    }

    public void recordSlowOperation(String cacheName, String operation, String key, long durationMs) {
        getSlowOperationCounter(cacheName).ifPresent(counter -> counter.increment());
    }

    public void recordCacheSize(String cacheName, long size) {
        if (meterRegistry.isPresent()) {
            cacheSizes.computeIfAbsent(cacheName, k -> {
                AtomicLong sizeGauge = new AtomicLong(0);
                meterRegistry.get().gauge(
                    "cache.size",
                    io.micrometer.core.instrument.Tags.of("cacheName", cacheName),
                    sizeGauge
                );
                return sizeGauge;
            }).set(size);
        }
    }

    private Optional<Counter> getHitCounter(String cacheName) {
        if (meterRegistry.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(hitCounters.computeIfAbsent(cacheName, name ->
            Counter.builder("cache.hits")
                .description("Number of cache hits")
                .tag("cacheName", name)
                .register(meterRegistry.get())
        ));
    }

    private Optional<Counter> getMissCounter(String cacheName) {
        if (meterRegistry.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(missCounters.computeIfAbsent(cacheName, name ->
            Counter.builder("cache.misses")
                .description("Number of cache misses")
                .tag("cacheName", name)
                .register(meterRegistry.get())
        ));
    }

    private Optional<Timer> getLatencyTimer(String cacheName) {
        if (meterRegistry.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(latencyTimers.computeIfAbsent(cacheName, name ->
            Timer.builder("cache.latency")
                .description("Cache operation latency")
                .tag("cacheName", name)
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry.get())
        ));
    }

    private Optional<Counter> getSlowOperationCounter(String cacheName) {
        if (meterRegistry.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(slowOperationCounters.computeIfAbsent(cacheName, name ->
            Counter.builder("cache.slow.operations")
                .description("Number of slow cache operations")
                .tag("cacheName", name)
                .register(meterRegistry.get())
        ));
    }
}
