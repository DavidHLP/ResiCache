package io.github.davidhlp.spring.cache.redis.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * metrics 关闭路径的 seam 契约:共享单例、无状态、注册不留痕。
 *
 * <p>回归的是 R1:关闭路径曾经是"永远没有 child 的 {@code CompositeMeterRegistry}",
 * 它不发布但仍然记住每一个被问过的 meter —— 动态命名的 cache 会让这张表活到 context 结束。
 */
@DisplayName("Disabled metrics seam")
class DisabledMetricsRegistryTest {

    @Test
    @DisplayName("关闭/无 registry:同一共享实例,反复注册后 meter 表仍为空")
    void disabledSeam_isSharedAndRetainsNothing() {
        assertThat(ResolvedMetrics.resolve(null, new MockEnvironment()).meterRegistry())
                .as("metrics 未开启")
                .isSameAs(DisabledMetricsRegistry.INSTANCE)
                .isSameAs(ResolvedMetrics.resolve(null,
                        new MockEnvironment().withProperty("resi-cache.metrics.enabled", "true"))
                        .meterRegistry())
                .isSameAs(ResolvedMetrics.resolve(null, new MockEnvironment()).meterRegistry());

        MeterRegistry seam = DisabledMetricsRegistry.INSTANCE;
        for (int i = 0; i < 64; i++) {
            Counter counter = seam.counter("resicache.probe.counter", "cache", "dynamic-cache-" + i);
            counter.increment();
            seam.timer("resicache.probe.timer", "cache", "dynamic-cache-" + i);

            assertThat(counter.count()).isZero();
        }

        assertThat(seam.getMeters())
                .as("无状态 sink:任何注册都不得进入 meter 表")
                .isEmpty();
        assertThat(seam.find("resicache.probe.counter").counter()).isNull();
    }
}
