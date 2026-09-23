package io.github.davidhlp.spring.cache.redis.cache;






import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;

/**
 * 提前过期任务的 Micrometer 指标注册与计数：从 {@code ThreadPoolEarlyExpirationExecutor} 抽出，
 * 将指标注册（3 个 Counter + 2 个 Gauge）与计数逻辑集中于单一协作类，无锁、线程安全。
 *
 * <p>{@code meterRegistry} 永不为 {@code null}：指标未启用（或应用无 {@code MeterRegistry}
 * bean）时它是共享无状态的 {@link DisabledMetricsRegistry#INSTANCE}（唯一判据
 * {@link MetricsWriter#disabled(MeterRegistry)}），此时构造期直接走 null
 * 分支——不构造 meter、不走 deny-all filter、不分配 Noop* counter/gauge，3 个 counter 字段
 * 保持 null，所有 record 方法为空操作。提取收益（locality）：原本散落在执行器构造器与各方法中
 * 的 Counter/Gauge 注册及 null 判定，现收敛为一处，执行器只需调用 {@code recordXxx()}。
 */
@Slf4j
final class RefreshTaskMetrics {

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
            this.submittedCounter = null;
            this.completedCounter = null;
            this.cancelledCounter = null;
            return;
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
