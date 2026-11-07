package io.github.davidhlp.spring.cache.redis.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Refresh lifecycle counters, finite outcome counters, and executor gauges.
 * Disabled registries retain no counters or outcome arrays. Completed counts future
 * terminal states, including cancellation, rather than completed Redis I/O.
 */
final class RefreshTaskMetrics {

    enum Outcome { MISSING, VERSION_CHANGED, BELOW_GRACE, SHORTENED, SNAPSHOT_CHANGED, TTL_UNCHANGED, REJECTED, CANCELLED }

    private final Counter[] outcomes;
    private final Counter submittedCounter;
    private final Counter completedCounter;
    private final Counter cancelledCounter;

    /**
     * 注册指标到给定 registry。
     *
     * @param meterRegistry   Micrometer registry（永不为 null；关闭路径为共享 no-op seam，
     *                        其上的注册不发布、不保留）
     * @param inFlight        活跃任务映射（用于 {@code prerefresh.active} Gauge）
     * @param executorService 线程池（为 {@link ThreadPoolExecutor} 时注册 {@code prerefresh.queue.size} Gauge）
     */
    public RefreshTaskMetrics(
            MeterRegistry meterRegistry,
            ConcurrentHashMap<String, CompletableFuture<Void>> inFlight,
            ExecutorService executorService) {
        if (MetricsWriter.disabled(meterRegistry)) {
            this.outcomes = null;
            this.submittedCounter = null;
            this.completedCounter = null;
            this.cancelledCounter = null;
            return;
        }
        this.outcomes = new Counter[Outcome.values().length];
        for (Outcome outcome : Outcome.values()) {
            outcomes[outcome.ordinal()] = MetricsWriter.counter(meterRegistry,
                    "prerefresh.outcome", "Refresh decisions and executor outcomes", "outcome",
                    outcome.name().toLowerCase(java.util.Locale.ROOT));
        }
        this.submittedCounter = MetricsWriter.counter(
                meterRegistry, "prerefresh.submitted", "Number of early-expiration tasks submitted");
        this.completedCounter = MetricsWriter.counter(
                meterRegistry, "prerefresh.completed", "Number of early-expiration tasks completed");
        this.cancelledCounter = MetricsWriter.counter(
                meterRegistry, "prerefresh.cancelled", "Number of early-expiration tasks cancelled");

        // Gauge: 活跃任务数
        MetricsWriter.gauge(meterRegistry, "prerefresh.active", inFlight, map -> map.size(),
                "Number of active early-expiration tasks");

        // Gauge: 队列大小
        if (executorService instanceof ThreadPoolExecutor tpe) {
            MetricsWriter.gauge(meterRegistry, "prerefresh.queue.size", tpe,
                    tpe2 -> tpe2.getQueue().size(), "Size of the early-expiration task queue",
                    "component", "prerefresh");
        }
    }

    void recordOutcome(Outcome outcome) {
        if (outcomes != null) {
            MetricsWriter.increment(outcomes[outcome.ordinal()]);
        }
    }

    /** 记录一次任务提交 */
    public void recordSubmitted() {
        if (submittedCounter != null) {
            MetricsWriter.increment(submittedCounter);
        }
    }

    /** 记录一次任务完成 */
    public void recordCompleted() {
        if (completedCounter != null) {
            MetricsWriter.increment(completedCounter);
        }
    }

    /** 记录一次任务取消 */
    public void recordCancelled() {
        if (cancelledCounter != null) {
            MetricsWriter.increment(cancelledCounter);
        }
    }

}
