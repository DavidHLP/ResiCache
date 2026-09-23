package io.github.davidhlp.spring.cache.redis.cache;

import io.micrometer.core.instrument.MeterRegistry;

/** Internal capability for handlers that own semantic metrics. */
interface MetricAttachable {
    void attachMeterRegistry(MeterRegistry registry);
}
