package io.github.davidhlp.spring.cache.redis.core.writer.support.refresh;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在有限的线程池上执行预刷新任务，同时防止每个键的重复提交。
 * <p>
 * 此执行器管理一个线程池，用于异步执行缓存预刷新操作。
 * 它通过跟踪正在进行的操作，确保任何给定时间每个缓存键只执行一个刷新任务。
 * 任务被提交到具有可配置核心和最大大小的有限线程池中，
 * 被拒绝的任务由调用者运行策略处理。
 * </p>
 * <p>
 * 执行器还通过统计和活动计数方法提供监控功能，
 * 并支持取消正在进行的刷新操作。
 * </p>
 * <p>
 * 失败的任务会自动重试，最多重试 {@value #MAX_RETRY_COUNT} 次。
 * </p>
 */
@Slf4j
@Component
public class ThreadPoolPreRefreshExecutor implements PreRefreshExecutor {

    /** 预刷新任务最大重试次数 */
    private static final int MAX_RETRY_COUNT = 3;
    
    /** 重试间隔（毫秒） */
    private static final long RETRY_DELAY_MS = 1000;

    private final ExecutorService executorService;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> inFlight;
    private static final String THREAD_NAME_PREFIX = "pre-refresh-";

    /** 独立调度器用于定期清理已完成任务 */
    private final ScheduledExecutorService cleanupScheduler;

    /** 清理间隔（毫秒） */
    private final long cleanupIntervalMs;

    /** Micrometer 计数器 */
    private final Counter submittedCounter;
    private final Counter completedCounter;
    private final Counter cancelledCounter;

    /**
     * 默认构造函数，创建具有默认配置的线程池执行器
     */
    public ThreadPoolPreRefreshExecutor() {
        this(createExecutor(), new ConcurrentHashMap<>(), null, 30_000L);
    }

    /**
     * 构造函数，允许注入自定义的执行器服务和进行中的任务映射
     *
     * @param executorService 线程池执行器服务
     * @param inFlight        正在进行中的任务映射
     * @param meterRegistry   Micrometer meter注册表（可选，为null时不注册指标）
     */
    ThreadPoolPreRefreshExecutor(
            ExecutorService executorService,
            ConcurrentHashMap<String, CompletableFuture<Void>> inFlight,
            MeterRegistry meterRegistry,
            long cleanupIntervalMs) {
        this.executorService = executorService;
        this.inFlight = inFlight;
        this.cleanupIntervalMs = cleanupIntervalMs;

        // 创建独立的清理调度器
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pre-refresh-cleanup");
            t.setDaemon(true);
            return t;
        });

        try {
            // 初始化 Micrometer 指标
            if (meterRegistry != null) {
                this.submittedCounter = Counter.builder("prerefresh.submitted")
                        .description("Number of pre-refresh tasks submitted")
                        .register(meterRegistry);
                this.completedCounter = Counter.builder("prerefresh.completed")
                        .description("Number of pre-refresh tasks completed")
                        .register(meterRegistry);
                this.cancelledCounter = Counter.builder("prerefresh.cancelled")
                        .description("Number of pre-refresh tasks cancelled")
                        .register(meterRegistry);

                // Gauge: 活跃任务数
                Gauge.builder("prerefresh.active", inFlight, map -> map.size())
                        .description("Number of active pre-refresh tasks")
                        .register(meterRegistry);

                // Gauge: 队列大小
                if (executorService instanceof ThreadPoolExecutor tpe) {
                    Gauge.builder("prerefresh.queue.size", tpe, tpe2 -> tpe2.getQueue().size())
                            .tag("component", "prerefresh")
                            .description("Size of the pre-refresh task queue")
                            .register(meterRegistry);
                }
            } else {
                this.submittedCounter = null;
                this.completedCounter = null;
                this.cancelledCounter = null;
            }

            log.info("ThreadPoolPreRefreshExecutor initialized with thread pool: core=2, max=10, queue=100, maxRetries={}", MAX_RETRY_COUNT);
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
                new PreRefreshThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 提交一个预刷新任务到执行器中
     * 如果给定键的任务已经在执行中，则跳过提交
     *
     * @param key  与预刷新任务关联的键
     * @param task 要执行的预刷新任务
     */
    @Override
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
                                            () -> executeWithRetry(k, task),
                                            executorService);

                            created.whenComplete(
                                    (result, throwable) -> {
                                        inFlight.remove(k, created);
                                        if (completedCounter != null) {
                                            completedCounter.increment();
                                        }
                                        if (throwable != null) {
                                            log.error("Async pre-refresh failed after all retries for key: {}", k, throwable);
                                        }
                                    });
                            return created;
                        });

        if (!scheduled.get() && !future.isDone()) {
            log.debug("Key {} is already being refreshed, skipping", key);
        }
    }

    /**
     * 带重试机制执行预刷新任务
     *
     * @param key  缓存键
     * @param task 要执行的任务
     */
    private void executeWithRetry(String key, Runnable task) {
        int attempt = 0;
        Exception lastException = null;
        
        while (attempt < MAX_RETRY_COUNT) {
            attempt++;
            try {
                log.debug("Starting async pre-refresh for key: {} (attempt {}/{})", key, attempt, MAX_RETRY_COUNT);
                task.run();
                log.debug("Completed async pre-refresh for key: {} (attempt {})", key, attempt);
                return; // 成功，退出
            } catch (Exception ex) {
                lastException = ex;
                log.warn("Async pre-refresh failed for key: {} (attempt {}/{}): {}", 
                        key, attempt, MAX_RETRY_COUNT, ex.getMessage());
                
                if (attempt < MAX_RETRY_COUNT) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Retry interrupted for key: {}, continuing with next attempt", key);
                        continue; // Continue to next retry attempt instead of exiting loop
                    }
                }
            }
        }
        
        // 所有重试都失败
        if (lastException != null) {
            log.error("Async pre-refresh failed after {} attempts for key: {}", MAX_RETRY_COUNT, key, lastException);
            throw new RuntimeException("Pre-refresh failed after " + MAX_RETRY_COUNT + " attempts", lastException);
        }
    }

    /**
     * 取消与给定键关联的预刷新任务
     *
     * @param key 要取消的预刷新任务的键
     */
    @Override
    public void cancel(String key) {
        if (key == null) {
            return;
        }
        CompletableFuture<Void> future = inFlight.remove(key);
        if (future != null) {
            if (cancelledCounter != null) {
                cancelledCounter.increment();
            }
            boolean cancelled = future.cancel(true);
            log.debug(
                    "Cancelled async pre-refresh for key: {} (cancelled={}, done={})",
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
    @Override
    public String getStats() {
        if (executorService instanceof ThreadPoolExecutor tpe) {
            return String.format(
                    "PreRefreshThreadPool[active=%d, poolSize=%d, queueSize=%d, completed=%d]",
                    tpe.getActiveCount(),
                    tpe.getPoolSize(),
                    tpe.getQueue().size(),
                    tpe.getCompletedTaskCount());
        }
        return "PreRefreshThreadPool[unknown]";
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
     * 获取当前正在进行的预刷新任务数量
     *
     * @return 正在进行的任务数量
     */
    @Override
    public int getActiveCount() {
        return inFlight.size();
    }

    /**
     * 清理已完成的任务，从进行中的映射中移除已完成的任务
     */
	private void cleanFinished() {
        inFlight.keySet().removeIf(key -> {
            CompletableFuture<Void> future = inFlight.get(key);
            return future != null && future.isDone();
        });
    }

    /**
     * 关闭执行器，释放所有资源
     * 此方法在应用关闭时自动调用
     */
    @Override
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down pre-refresh executor thread pool...");

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
     * 为预刷新线程创建命名线程的工厂类
     */
    private static final class PreRefreshThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        /**
         * 创建一个新的预刷新线程
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
