package com.david.spring.cache.redis.core.writer.support.refresh;

/**
 * Coordinates asynchronous pre-refresh execution and tracks in-flight jobs.
 */
public interface PreRefreshExecutor {

    boolean isRefreshing(String key);

    void submit(String key, Runnable task);

    String getStats();

    int getActiveCount();

    void cleanup();

    void shutdown();
}