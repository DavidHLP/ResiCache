package com.david.spring.cache.redis.protection;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存雪崩防护：统一的“过期时间随机化（TTL 抖动）”策略。
 *
 * <p>核心思路：在基础 TTL 的基础上，按比例随机缩短有效期，常见区间为 5% ~ 20%， 以此打散大量 key 的同时失效时间，降低同一时间点的回源洪峰风险。
 */
@Component
public final class CacheAvalanche {

    /** 最小随机缩减比例（例如 0.05 表示 5%） */
    @Getter @Setter private double minJitterRatio = 0.05d;

    /** 最大随机缩减比例（例如 0.20 表示 20%） */
    @Getter @Setter private double maxJitterRatio = 0.20d;

    /** TTL 的最小下限（秒） */
    @Getter @Setter private long minSeconds = 1L;

    /**
     * 基于比例的 TTL 抖动：在 [minJitterRatio, maxJitterRatio) 之间取随机值，按该比例缩短 TTL。
     *
     * @param baseSeconds 原始 TTL（秒）
     * @return 随机化后的 TTL（秒）
     */
    public long jitterTtlSeconds(long baseSeconds) {
        if (baseSeconds <= minSeconds) {
            return baseSeconds;
        }
        double low = Math.max(0.0d, Math.min(minJitterRatio, 0.99d));
        double high = Math.max(low, Math.min(maxJitterRatio, 0.99d));
        double ratio = (high > low) ? ThreadLocalRandom.current().nextDouble(low, high) : low;
        long jittered = (long) Math.floor(baseSeconds * (1.0d - ratio));
        return Math.max(minSeconds, jittered);
    }
}
