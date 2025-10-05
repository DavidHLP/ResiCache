package com.david.spring.cache.redis.core.writer.support.protect.ttl;

import java.time.Duration;

/** Policy interface encapsulating TTL calculation and evaluation rules. */
public interface TtlPolicy {

    boolean shouldApply(Duration ttl);

    long calculateFinalTtl(Long baseTtl, boolean randomTtl, float variance);

    boolean isExpired(long createdTime, long ttlSeconds);

    long getRemainingTtl(long createdTime, long ttlSeconds);

    boolean shouldPreRefresh(long createdTime, long ttlSeconds, double threshold);

    long fromDuration(Duration duration);

    Duration toDuration(long ttlSeconds);

    boolean isValidTtl(long ttlSeconds);
}
