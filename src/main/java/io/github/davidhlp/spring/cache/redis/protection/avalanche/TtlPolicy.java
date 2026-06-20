package io.github.davidhlp.spring.cache.redis.protection.avalanche;

import java.time.Duration;

/**
 * 封装TTL计算和评估规则的策略接口。
 */
public interface TtlPolicy {

    boolean shouldApply(Duration ttl);

    long calculateFinalTtl(Long baseTtl, boolean randomTtl, float variance);

    boolean shouldEarlyExpiration(long createdTime, long ttlSeconds, double threshold);
}
