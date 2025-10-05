package com.david.spring.cache.redis.core.writer.support.refresh;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Default evaluator based on elapsed TTL percentage.
 */
@Component
@RequiredArgsConstructor
public class DefaultPreRefreshEvaluator implements PreRefreshEvaluator {

    private final Clock clock;

    @Override
    public boolean shouldPreRefresh(long createdTime, long ttlSeconds, double threshold) {
        if (ttlSeconds <= 0 || threshold <= 0 || threshold >= 1) {
            return false;
        }

        long elapsedTime = clock.millis() - createdTime;
        long totalTime = ttlSeconds * 1000;
        double usedRatio = (double) elapsedTime / totalTime;
        return usedRatio >= (1 - threshold);
    }

    @Override
    public long calculateTriggerTime(long ttlSeconds, double threshold) {
        if (ttlSeconds <= 0 || threshold <= 0 || threshold >= 1) {
            return -1;
        }
        return (long) (ttlSeconds * (1 - threshold));
    }
}