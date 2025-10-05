package com.david.spring.cache.redis.core.writer.support.refresh;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Coordinates pre-refresh evaluation and asynchronous execution.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreRefreshSupport {

    private final PreRefreshEvaluator evaluator;
    private final PreRefreshExecutor executor;

    public boolean isRefreshing(String key) {
        return executor.isRefreshing(key);
    }

    public void submitAsyncRefresh(String key, Runnable refreshTask) {
        executor.submit(key, refreshTask);
    }

    public boolean shouldPreRefresh(long createdTime, long ttl, double threshold) {
        return evaluator.shouldPreRefresh(createdTime, ttl, threshold);
    }

    public long calculatePreRefreshTriggerTime(long ttl, double threshold) {
        return evaluator.calculateTriggerTime(ttl, threshold);
    }

    public String getThreadPoolStats() {
        return executor.getStats();
    }

    public int getRefreshingKeyCount() {
        return executor.getActiveCount();
    }

    public void cleanupCompletedRefreshes() {
        executor.cleanup();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}