package com.david.spring.cache.redis.core.writer.support.protect.ttl;

import java.time.Duration;

/**
 * 封装TTL计算和评估规则的策略接口。
 */
public interface TtlPolicy {

    boolean shouldApply(Duration ttl);

    long calculateFinalTtl(Long baseTtl, boolean randomTtl, float variance);

    boolean shouldPreRefresh(long createdTime, long ttlSeconds, double threshold);
}
