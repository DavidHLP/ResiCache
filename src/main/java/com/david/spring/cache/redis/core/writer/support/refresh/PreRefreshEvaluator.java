package com.david.spring.cache.redis.core.writer.support.refresh;

/**
 * Decides whether a cached entry should trigger pre-refresh and related timing details.
 */
public interface PreRefreshEvaluator {

    boolean shouldPreRefresh(long createdTime, long ttlSeconds, double threshold);

    long calculateTriggerTime(long ttlSeconds, double threshold);
}