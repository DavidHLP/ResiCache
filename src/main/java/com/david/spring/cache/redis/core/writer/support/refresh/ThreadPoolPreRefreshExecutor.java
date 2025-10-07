package com.david.spring.cache.redis.core.writer.support.refresh;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes pre-refresh tasks on a bounded thread pool while preventing duplicate submissions per key.
 */
@Slf4j
@Component
public class ThreadPoolPreRefreshExecutor implements PreRefreshExecutor {

    private final ExecutorService executorService;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> inFlight;

    public ThreadPoolPreRefreshExecutor() {
        this(createExecutor(), new ConcurrentHashMap<>());
    }

    ThreadPoolPreRefreshExecutor(
            ExecutorService executorService, ConcurrentHashMap<String, CompletableFuture<Void>> inFlight) {
        this.executorService = executorService;
        this.inFlight = inFlight;
        log.info("ThreadPoolPreRefreshExecutor initialized with thread pool: core=2, max=10, queue=100");
    }

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
                                            () -> {
                                                try {
                                                    log.debug("Starting async pre-refresh for key: {}", k);
                                                    task.run();
                                                    log.debug("Completed async pre-refresh for key: {}", k);
                                                } catch (Exception ex) {
                                                    log.error(
                                                            "Failed to execute async pre-refresh for key: {}",
                                                            k,
                                                            ex);
                                                    throw ex;
                                                }
                                            },
                                            executorService);

                            created.whenComplete(
                                    (result, throwable) -> {
                                        inFlight.remove(k, created);
                                        if (throwable != null) {
                                            log.error("Async pre-refresh failed for key: {}", k, throwable);
                                        }
                                    });
                            return created;
                        });

        if (!scheduled.get() && future != null && !future.isDone()) {
            log.debug("Key {} is already being refreshed, skipping", key);
        }
    }

    @Override
    public void cancel(String key) {
        if (key == null) {
            return;
        }
        CompletableFuture<Void> future = inFlight.remove(key);
        if (future != null) {
            boolean cancelled = future.cancel(true);
            log.debug(
                    "Cancelled async pre-refresh for key: {} (cancelled={}, done={})",
                    key,
                    cancelled,
                    future.isDone());
        }
    }

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

    @Override
    public int getActiveCount() {
        cleanFinished();
        return inFlight.size();
    }

	private void cleanFinished() {
        for (Map.Entry<String, CompletableFuture<Void>> entry : inFlight.entrySet()) {
            if (entry.getValue().isDone()) {
                inFlight.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down pre-refresh executor thread pool...");
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

    private static final class PreRefreshThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "pre-refresh-" + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }
}
