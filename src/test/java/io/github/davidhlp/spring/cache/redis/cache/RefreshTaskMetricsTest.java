package io.github.davidhlp.spring.cache.redis.cache;






import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * RefreshTaskMetrics 单元测试
 *
 * <p>覆盖 meterRegistry=null(空操作)与注册场景(3 Counter + 2 Gauge 注册 + record 计数)。
 * 纯 Micrometer 注册逻辑,无 testcontainers/线程池依赖。这是 C06 抽出 RefreshTaskMetrics
 * 的直接单测收益。
 */
@DisplayName("RefreshTaskMetrics Tests")
class RefreshTaskMetricsTest {

    @Test
    @DisplayName("null meterRegistry — record methods are no-op, no exception")
    void recordMethods_nullRegistry_noOp() {
        RefreshTaskMetrics metrics = new RefreshTaskMetrics(
                null, new ConcurrentHashMap<>(), Executors.newSingleThreadExecutor());

        assertThat(DisabledMetricsRegistry.INSTANCE.getMeters()).isEmpty();
        assertThatCode(() -> {
            metrics.recordSubmitted();
            metrics.recordCompleted();
            metrics.recordCancelled();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("recordSubmitted increments prerefresh.submitted counter")
    void recordSubmitted_incrementsCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefreshTaskMetrics metrics = new RefreshTaskMetrics(
                registry, new ConcurrentHashMap<>(), Executors.newSingleThreadExecutor());

        metrics.recordSubmitted();
        metrics.recordSubmitted();

        org.assertj.core.api.Assertions.assertThat(
                registry.counter("prerefresh.submitted").count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("recordCompleted increments prerefresh.completed counter")
    void recordCompleted_incrementsCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefreshTaskMetrics metrics = new RefreshTaskMetrics(
                registry, new ConcurrentHashMap<>(), Executors.newSingleThreadExecutor());

        metrics.recordCompleted();

        org.assertj.core.api.Assertions.assertThat(
                registry.counter("prerefresh.completed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordCancelled increments prerefresh.cancelled counter")
    void recordCancelled_incrementsCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefreshTaskMetrics metrics = new RefreshTaskMetrics(
                registry, new ConcurrentHashMap<>(), Executors.newSingleThreadExecutor());

        metrics.recordCancelled();

        org.assertj.core.api.Assertions.assertThat(
                registry.counter("prerefresh.cancelled").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("关闭 seam — 3 个 counter 都不注册(字段保持 null,record 走 null 短路)")
    void disabledSeam_registersNoCounters() {
        RefreshTaskMetrics metrics = new RefreshTaskMetrics(
                DisabledMetricsRegistry.INSTANCE,
                new ConcurrentHashMap<>(),
                Executors.newSingleThreadExecutor());

        assertThat(DisabledMetricsRegistry.INSTANCE.getMeters()).isEmpty();
        assertThatCode(() -> {
            metrics.recordSubmitted();
            metrics.recordCompleted();
            metrics.recordCancelled();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("启用 registry — 3 个 counter 全部注册")
    void enabledRegistry_registersAllCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefreshTaskMetrics metrics = new RefreshTaskMetrics(
                registry, new ConcurrentHashMap<>(), Executors.newSingleThreadExecutor());

        assertThat(registry.getMeters().stream()
                .filter(meter -> meter.getId().getType() == Meter.Type.COUNTER)
                .count()).isEqualTo(3);
    }

    @Test
    @DisplayName("ThreadPoolExecutor executor registers prerefresh.queue.size gauge")
    void constructor_threadPoolExecutor_registersQueueGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConcurrentHashMap<String, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();
        // newCachedThreadPool 返回 ThreadPoolExecutor,触发 queue.size gauge 注册
        ExecutorService executor = Executors.newCachedThreadPool();

        new RefreshTaskMetrics(registry, inFlight, executor);

        org.assertj.core.api.Assertions.assertThat(
                registry.find("prerefresh.queue.size").gauge()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(
                registry.find("prerefresh.active").gauge()).isNotNull();
    }
}
