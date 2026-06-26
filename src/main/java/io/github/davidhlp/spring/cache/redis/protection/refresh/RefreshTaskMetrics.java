package io.github.davidhlp.spring.cache.redis.protection.refresh;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 提前过期任务的 Micrometer 指标注册与计数：从 {@code ThreadPoolEarlyExpirationExecutor} 抽出，
 * 将指标注册（3 个 Counter + 2 个 Gauge）与计数逻辑集中于单一协作类，无锁、线程安全。
 *
 * <p>{@code meterRegistry} 为 {@code null} 时不注册任何指标，所有 record 方法为空操作，
 * 支持测试与无指标场景。提取收益（locality）：原本散落在执行器构造器与各方法中的
 * Counter/Gauge 注册及 null 判定，现收敛为一处，执行器只需调用 {@code recordXxx()}。
 */
@Slf4j
public final class RefreshTaskMetrics {

    private final Counter submittedCounter;
    private final Counter completedCounter;
    private final Counter cancelledCounter;

    /**
     * 注册指标到给定 registry。
     *
     * @param meterRegistry   Micrometer registry（null 则不注册，所有计数为空操作）
     * @param inFlight        活跃任务映射（用于 {@code prerefresh.active} Gauge）
     * @param executorService 线程池（为 {@link ThreadPoolExecutor} 时注册 {@code prerefresh.queue.size} Gauge）
     */
    public RefreshTaskMetrics(
            MeterRegistry meterRegistry,
            ConcurrentHashMap<String, CompletableFuture<Void>> inFlight,
            ExecutorService executorService) {
        if (meterRegistry == null) {
            this.submittedCounter = null;
            this.completedCounter = null;
            this.cancelledCounter = null;
            return;
        }
        this.submittedCounter = Counter.builder("prerefresh.submitted")
                .description("Number of early-expiration tasks submitted")
                .register(meterRegistry);
        this.completedCounter = Counter.builder("prerefresh.completed")
                .description("Number of early-expiration tasks completed")
                .register(meterRegistry);
        this.cancelledCounter = Counter.builder("prerefresh.cancelled")
                .description("Number of early-expiration tasks cancelled")
                .register(meterRegistry);

        // Gauge: 活跃任务数
        Gauge.builder("prerefresh.active", inFlight, map -> map.size())
                .description("Number of active early-expiration tasks")
                .register(meterRegistry);

        // Gauge: 队列大小
        if (executorService instanceof ThreadPoolExecutor tpe) {
            Gauge.builder("prerefresh.queue.size", tpe, tpe2 -> tpe2.getQueue().size())
                    .tag("component", "prerefresh")
                    .description("Size of the early-expiration task queue")
                    .register(meterRegistry);
        }
    }

    /** 记录一次任务提交 */
    public void recordSubmitted() {
        if (submittedCounter != null) {
            submittedCounter.increment();
        }
    }

    /** 记录一次任务完成 */
    public void recordCompleted() {
        if (completedCounter != null) {
            completedCounter.increment();
        }
    }

    /** 记录一次任务取消 */
    public void recordCancelled() {
        if (cancelledCounter != null) {
            cancelledCounter.increment();
        }
    }
}
