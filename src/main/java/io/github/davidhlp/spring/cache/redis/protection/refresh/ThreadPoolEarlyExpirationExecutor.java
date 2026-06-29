package io.github.davidhlp.spring.cache.redis.protection.refresh;

import io.micrometer.core.instrument.MeterRegistry;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在有限的线程池上执行提前过期任务，同时防止每个键的重复提交。
 *
 * <p>本类是对外契约门面（{@code @Component} +
 * 反射可见的 {@code executorService}/{@code cleanupScheduler} 字段），内部职责委托给两个协作类：
 * <ul>
 *   <li>{@link RefreshRetryPolicy} —— 同步重试循环（纯函数，独立可测）</li>
 *   <li>{@link RefreshTaskMetrics} —— Micrometer 指标注册与计数（无锁）</li>
 * </ul>
 * 去重提交（{@code inFlight} + {@code executorService}）与生命周期（清理调度、shutdown）
 * 因与门面反射字段紧耦合而保留在此。
 *
 * <p>失败的任务由 {@link RefreshRetryPolicy} 自动重试，最多 {@value RefreshRetryPolicy#MAX_RETRY_COUNT} 次。
 */
@Slf4j
@Component
public class ThreadPoolEarlyExpirationExecutor {

    private final ExecutorService executorService;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> inFlight;
    private static final String THREAD_NAME_PREFIX = "early-expiration-";

    /** 独立调度器用于定期清理已完成任务 */
    private final ScheduledExecutorService cleanupScheduler;

    /** 清理间隔（毫秒） */
    private final long cleanupIntervalMs;

    private final RefreshRetryPolicy retryPolicy;
    private final RefreshTaskMetrics metrics;

    /**
     * 默认构造函数，创建具有默认配置的线程池执行器
     */
    public ThreadPoolEarlyExpirationExecutor() {
        this(createExecutor(), new ConcurrentHashMap<>(), null, 30_000L);
    }

    /**
     * 构造函数，允许注入自定义的执行器服务和进行中的任务映射
     *
     * @param executorService 线程池执行器服务
     * @param inFlight        正在进行中的任务映射
     * @param meterRegistry   Micrometer meter注册表（可选，为null时不注册指标）
     * @param cleanupIntervalMs 清理调度器周期（毫秒）
     */
    ThreadPoolEarlyExpirationExecutor(
            ExecutorService executorService,
            ConcurrentHashMap<String, CompletableFuture<Void>> inFlight,
            MeterRegistry meterRegistry,
            long cleanupIntervalMs) {
        this.executorService = executorService;
        this.inFlight = inFlight;
        this.cleanupIntervalMs = cleanupIntervalMs;
        this.retryPolicy = new RefreshRetryPolicy();

        // 创建独立的清理调度器
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "early-expiration-cleanup");
            t.setDaemon(true);
            return t;
        });

        try {
            // 初始化 Micrometer 指标（注册逻辑收敛于 RefreshTaskMetrics）
            this.metrics = new RefreshTaskMetrics(meterRegistry, inFlight, executorService);
            log.info("ThreadPoolEarlyExpirationExecutor initialized with thread pool: core=2, max=10, queue=100, maxRetries={}",
                    RefreshRetryPolicy.MAX_RETRY_COUNT);
        } catch (RuntimeException e) {
            // 初始化失败时，确保清理已创建的资源
            cleanupScheduler.shutdownNow();
            executorService.shutdownNow();
            throw e;
        }
    }

    /**
     * 创建具有预定义配置的线程池执行器
     *
     * @return 配置好的线程池执行器
     */
    private static ExecutorService createExecutor() {
        return new ThreadPoolExecutor(
                2,
                10,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new EarlyExpirationThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 提交一个提前过期任务到执行器中
     * 如果给定键的任务已经在执行中，则跳过提交
     *
     * @param key  与提前过期任务关联的键
     * @param task 要执行的提前过期任务
     */
    public void submit(String key, Runnable task) {
        if (key == null || task == null) {
            return;
        }
        AtomicBoolean scheduled = new AtomicBoolean(false);

        CompletableFuture<Void> future =
                inFlight.computeIfAbsent(
                        key,
                        k -> {
                            scheduled.set(true);
                            CompletableFuture<Void> created =
                                    CompletableFuture.runAsync(
                                            () -> retryPolicy.executeWithRetry(k, task),
                                            executorService);

                            created.whenComplete(
                                    (result, throwable) -> {
                                        inFlight.remove(k, created);
                                        metrics.recordCompleted();
                                        if (throwable != null) {
                                            log.error("Async early-expiration failed after all retries for key: {}", k, throwable);
                                        }
                                    });
                            return created;
                        });

        if (scheduled.get()) {
            metrics.recordSubmitted();
        }
        if (!scheduled.get() && !future.isDone()) {
            log.debug("Key {} is already being refreshed, skipping", key);
        }
    }

    /**
     * 取消与给定键关联的提前过期任务
     *
     * @param key 要取消的提前过期任务的键
     */
    public void cancel(String key) {
        if (key == null) {
            return;
        }
        CompletableFuture<Void> future = inFlight.remove(key);
        if (future != null) {
            metrics.recordCancelled();
            boolean cancelled = future.cancel(true);
            log.debug(
                    "Cancelled async early-expiration for key: {} (cancelled={}, done={})",
                    key,
                    cancelled,
                    future.isDone());
        }
    }

    /**
     * 获取线程池的统计信息
     *
     * @return 包含活动线程数、池大小、队列大小和已完成任务数的字符串
     */
    public String getStats() {
        if (executorService instanceof ThreadPoolExecutor tpe) {
            return String.format(
                    "EarlyExpirationThreadPool[active=%d, poolSize=%d, queueSize=%d, completed=%d]",
                    tpe.getActiveCount(),
                    tpe.getPoolSize(),
                    tpe.getQueue().size(),
                    tpe.getCompletedTaskCount());
        }
        return "EarlyExpirationThreadPool[unknown]";
    }

    /**
     * 启动定期清理调度器
     */
    @PostConstruct
    public void initCleanupScheduler() {
        cleanupScheduler.scheduleAtFixedRate(
                this::cleanFinished,
                cleanupIntervalMs,
                cleanupIntervalMs,
                TimeUnit.MILLISECONDS);
        log.info("Pre-refresh cleanup scheduler started with interval={}ms", cleanupIntervalMs);
    }

    /**
     * 获取当前正在进行的提前过期任务数量
     *
     * @return 正在进行的任务数量
     */
    public int getActiveCount() {
        return inFlight.size();
    }

    /**
     * 清理已完成的任务，从进行中的映射中移除已完成的任务
     */
    private void cleanFinished() {
        inFlight.entrySet().removeIf(e -> e.getValue() != null && e.getValue().isDone());
    }

    /**
     * 关闭执行器，释放所有资源
     * 此方法在应用关闭时自动调用
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down early-expiration executor thread pool...");

        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
                log.warn("Pre-refresh cleanup scheduler did not terminate gracefully, forced shutdown");
            } else {
                log.info("Pre-refresh cleanup scheduler shut down successfully");
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
            log.error("Pre-refresh cleanup scheduler shutdown interrupted", e);
        }

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                log.warn("Pre-refresh executor did not terminate gracefully, forced shutdown");
            } else {
                log.info("Pre-refresh executor shut down successfully");
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
            log.error("Pre-refresh executor shutdown interrupted", e);
        }
    }

    /**
     * 为提前过期线程创建命名线程的工厂类
     */
    private static final class EarlyExpirationThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        /**
         * 创建一个新的提前过期线程
         *
         * @param r 线程要执行的任务
         * @return 配置好的线程实例
         */
        @Override
        public Thread newThread(@NonNull Runnable r) {
            Thread thread = new Thread(r, THREAD_NAME_PREFIX + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }
}
