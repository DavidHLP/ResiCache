package com.david.spring.cache.redis.core.writer.support.refresh;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/** Coordinates pre-refresh evaluation and asynchronous execution. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreRefreshSupport {

    private final PreRefreshExecutor executor;

    public void submitAsyncRefresh(String key, Runnable refreshTask) {
        if (key == null || refreshTask == null) {
            log.warn("Skipping async pre-refresh submission due to missing key or task");
            return;
        }
        executor.submit(key, refreshTask);
    }

    public String getThreadPoolStats() {
        return executor.getStats();
    }

    public int getRefreshingKeyCount() {
        return executor.getActiveCount();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
