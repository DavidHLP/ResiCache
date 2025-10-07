package com.david.spring.cache.redis.core.writer.support.refresh;

/**
 * Coordinates asynchronous pre-refresh execution and tracks in-flight jobs.
 */
public interface PreRefreshExecutor {

	void submit(String key, Runnable task);

	void cancel(String key);

	String getStats();

	int getActiveCount();

	void shutdown();
}
