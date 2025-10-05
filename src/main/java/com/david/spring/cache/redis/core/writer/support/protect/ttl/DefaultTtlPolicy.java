package com.david.spring.cache.redis.core.writer.support.protect.ttl;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Default TTL policy leveraging an injectable clock for testability. */
@Component
@RequiredArgsConstructor
public class DefaultTtlPolicy implements TtlPolicy {

    private final Clock clock;

    @Override
    public boolean shouldApply(Duration ttl) {
        return ttl != null && !ttl.isZero() && !ttl.isNegative();
    }

    @Override
    public long calculateFinalTtl(Long baseTtl, boolean randomTtl, float variance) {
        if (baseTtl == null || baseTtl <= 0) {
            return -1;
        }
        if (!randomTtl || variance <= 0) {
            return baseTtl;
        }

        variance = Math.min(1.0f, Math.max(0.0f, variance));

        double randomFactor = ThreadLocalRandom.current().nextGaussian();
        randomFactor = Math.max(-3.0, Math.min(3.0, randomFactor));

        long offset = (long) (baseTtl * variance * randomFactor / 3.0);
        long result = baseTtl + offset;
        return Math.max(1, Math.min(result, baseTtl * 2));
    }

    @Override
    public boolean isExpired(long createdTime, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return false;
        }
        return (currentTimeMillis() - createdTime) > (ttlSeconds * 1000);
    }

    @Override
    public long getRemainingTtl(long createdTime, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return -1;
        }
        long elapsedSeconds = (currentTimeMillis() - createdTime) / 1000;
        return Math.max(0, ttlSeconds - elapsedSeconds);
    }

    @Override
    public boolean shouldPreRefresh(long createdTime, long ttlSeconds, double threshold) {
        if (ttlSeconds <= 0 || threshold <= 0 || threshold >= 1) {
            return false;
        }

        long elapsedTime = currentTimeMillis() - createdTime;
        long totalTime = ttlSeconds * 1000;
        double usedRatio = (double) elapsedTime / totalTime;
        return usedRatio >= (1 - threshold);
    }

    @Override
    public long fromDuration(Duration duration) {
        return duration != null ? duration.getSeconds() : 0;
    }

    @Override
    public Duration toDuration(long ttlSeconds) {
        return ttlSeconds > 0 ? Duration.ofSeconds(ttlSeconds) : Duration.ZERO;
    }

    @Override
    public boolean isValidTtl(long ttlSeconds) {
        return ttlSeconds > 0;
    }

    private long currentTimeMillis() {
        return clock.millis();
    }
}
