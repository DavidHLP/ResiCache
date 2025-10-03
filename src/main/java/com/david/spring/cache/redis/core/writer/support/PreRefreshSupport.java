package com.david.spring.cache.redis.core.writer.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 预刷新支持类 提供缓存预刷新相关的功能，包括同步和异步预刷新模式 */
@Slf4j
@Component
public class PreRefreshSupport {

    /** 异步预刷新任务线程池 */
    private final ExecutorService preRefreshExecutor;

    /** 正在刷新的key集合，防止重复刷新 */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> refreshingKeys;

    public PreRefreshSupport() {
        // 创建线程池：核心线程数2，最大线程数10，队列容量100
        this.preRefreshExecutor =
                new ThreadPoolExecutor(
                        2,
                        10,
                        60L,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(100),
                        new PreRefreshThreadFactory(),
                        new ThreadPoolExecutor.CallerRunsPolicy());
        this.refreshingKeys = new ConcurrentHashMap<>();
        log.info("PreRefreshSupport initialized with thread pool: core=2, max=10, queue=100");
    }

    /**
     * 判断key是否正在刷新中
     *
     * @param key 缓存key
     * @return true表示正在刷新
     */
    public boolean isRefreshing(String key) {
        CompletableFuture<Void> future = refreshingKeys.get(key);
        return future != null && !future.isDone();
    }

    /**
     * 提交异步预刷新任务
     *
     * @param key         缓存key
     * @param refreshTask 刷新任务
     */
    public void submitAsyncRefresh(String key, Runnable refreshTask) {
        // 检查是否已经在刷新中
        if (isRefreshing(key)) {
            log.debug("Key {} is already being refreshed, skipping", key);
            refreshingKeys.get(key);
            return;
        }

        // 提交异步刷新任务
        CompletableFuture<Void> future =
                CompletableFuture.runAsync(
                        () -> {
                            try {
                                log.debug("Starting async pre-refresh for key: {}", key);
                                refreshTask.run();
                                log.debug("Completed async pre-refresh for key: {}", key);
                            } catch (Exception e) {
                                log.error(
                                        "Failed to execute async pre-refresh for key: {}", key, e);
                                throw e;
                            }
                        },
                        preRefreshExecutor);

        // 记录刷新状态
        refreshingKeys.put(key, future);

        // 完成后清理
        future.whenComplete(
                (result, throwable) -> {
                    refreshingKeys.remove(key);
                    if (throwable != null) {
                        log.error("Async pre-refresh failed for key: {}", key, throwable);
                    }
                });

    }

    /**
     * 检查是否需要预刷新（委托给TtlSupport） 此方法主要用于逻辑封装，实际判断逻辑在TtlSupport中
     *
     * @param createdTime 缓存创建时间
     * @param ttl 总TTL（秒）
     * @param threshold 预刷新阈值（0-1之间）
     * @return true表示需要预刷新
     */
    public boolean shouldPreRefresh(long createdTime, long ttl, double threshold) {
        if (ttl <= 0 || threshold <= 0 || threshold >= 1) {
            return false;
        }

        long elapsedTime = System.currentTimeMillis() - createdTime;
        long totalTime = ttl * 1000;
        double usedRatio = (double) elapsedTime / totalTime;

        // 当使用时间超过 (1 - threshold) 时触发预刷新
        return usedRatio >= (1 - threshold);
    }

    /**
     * 计算预刷新触发时间点
     *
     * @param ttl 总TTL（秒）
     * @param threshold 预刷新阈值
     * @return 预刷新触发时间点（从创建开始的秒数）
     */
    public long calculatePreRefreshTriggerTime(long ttl, double threshold) {
        if (ttl <= 0 || threshold <= 0 || threshold >= 1) {
            return -1;
        }
        return (long) (ttl * (1 - threshold));
    }

    /**
     * 获取线程池统计信息
     *
     * @return 统计信息字符串
     */
    public String getThreadPoolStats() {
        if (preRefreshExecutor instanceof ThreadPoolExecutor tpe) {
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
     * 获取正在刷新的key数量
     *
     * @return 数量
     */
    public int getRefreshingKeyCount() {
        return refreshingKeys.size();
    }

    /** 清理已完成的刷新任务 */
    public void cleanupCompletedRefreshes() {
        refreshingKeys.entrySet().removeIf(entry -> entry.getValue().isDone());
    }

    /** 应用销毁时关闭线程池 */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down PreRefreshSupport thread pool...");
        preRefreshExecutor.shutdown();
        try {
            if (!preRefreshExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                preRefreshExecutor.shutdownNow();
                log.warn("PreRefresh thread pool did not terminate gracefully, forced shutdown");
            } else {
                log.info("PreRefresh thread pool shut down successfully");
            }
        } catch (InterruptedException e) {
            preRefreshExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            log.error("PreRefresh thread pool shutdown interrupted", e);
        }
    }

    /** 自定义线程工厂，用于创建预刷新线程 */
    private static class PreRefreshThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            String namePrefix = "pre-refresh-";
            Thread thread = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }
}
