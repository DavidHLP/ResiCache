package com.david.spring.cache.redis.core.writer.support;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class TtlSupport {

    /**
     * 判断是否应该应用TTL
     */
    public boolean shouldApplyTtl(Duration ttl) {
        return ttl != null && !ttl.isZero() && !ttl.isNegative();
    }

    /**
     * 计算最终的TTL（包含随机化）
     *
     * @param baseTtl 基础TTL（秒）
     * @param randomTtl 是否启用随机TTL
     * @param variance 随机波动比例（0-1之间）
     * @return 最终的TTL（秒），如果baseTtl无效则返回-1
     */
    public long calculateFinalTtl(Long baseTtl, boolean randomTtl, float variance) {
        if (baseTtl == null || baseTtl <= 0) {
            return -1;
        }
        if (!randomTtl) {
            return baseTtl;
        }

        long randomOffset =
                (long) (baseTtl * variance * (ThreadLocalRandom.current().nextFloat() - 0.5) * 2);
        long result = baseTtl + randomOffset;

        return Math.max(1, result);
    }

    /**
     * 检查缓存是否过期
     */
    public boolean isExpired(long createdTime, long ttl) {
        if (ttl <= 0) {
            return false;
        }
        return (System.currentTimeMillis() - createdTime) > (ttl * 1000);
    }

    /**
     * 获取剩余TTL
     */
    public long getRemainingTtl(long createdTime, long ttl) {
        if (ttl <= 0) {
            return -1;
        }
        long elapsedSeconds = (System.currentTimeMillis() - createdTime) / 1000;
        return Math.max(0, ttl - elapsedSeconds);
    }

    /**
     * 判断是否需要预刷新
     *
     * @param createdTime 缓存创建时间
     * @param ttl 总TTL（秒）
     * @param threshold 预刷新阈值（0-1之间，例如0.3表示剩余30%时触发）
     * @return true表示需要预刷新
     */
    public boolean shouldPreRefresh(long createdTime, long ttl, double threshold) {
        if (ttl <= 0 || threshold <= 0 || threshold >= 1) {
            return false;
        }

        long elapsedTime = System.currentTimeMillis() - createdTime;
        long totalTime = ttl * 1000;
        double usedRatio = (double) elapsedTime / totalTime;

        // 当使用时间超过 (1 - threshold) 时触发预刷新
        return usedRatio >= (1 - threshold);
    }

    /**
     * 转换Duration为秒
     */
    public long fromDuration(Duration duration) {
        return duration != null ? duration.getSeconds() : 0;
    }

    /**
     * 转换秒为Duration
     */
    public Duration toDuration(long ttlInSeconds) {
        return ttlInSeconds > 0 ? Duration.ofSeconds(ttlInSeconds) : Duration.ZERO;
    }

    /**
     * 验证TTL是否有效
     */
    public boolean isValidTtl(long ttl) {
        return ttl > 0;
    }
}
