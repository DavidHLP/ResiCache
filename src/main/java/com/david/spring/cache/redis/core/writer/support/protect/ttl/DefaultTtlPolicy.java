package com.david.spring.cache.redis.core.writer.support.protect.ttl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 默认的TTL策略，利用可注入的时钟以提高可测试性。
 */
@Component
@RequiredArgsConstructor
public class DefaultTtlPolicy implements TtlPolicy {

    private final Clock clock;

    /**
     * 判断给定的Duration是否应该应用
     *
     * @param ttl Duration类型的TTL值
     * @return 如果ttl不为null且不为零且不为负数则返回true，否则返回false
     */
    @Override
    public boolean shouldApply(Duration ttl) {
        return ttl != null && !ttl.isZero() && !ttl.isNegative();
    }

    /**
     * 计算最终的TTL值，支持随机化处理
     *
     * @param baseTtl 基础TTL值
     * @param randomTtl 是否启用随机化
     * @param variance 随机化方差
     * @return 计算后的TTL值，如果baseTtl无效则返回-1
     */
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

    /**
     * 判断是否应该预刷新缓存项
     *
     * @param createdTime 创建时间戳（毫秒）
     * @param ttlSeconds TTL时间（秒）
     * @param threshold 预刷新阈值
     * @return 如果应该预刷新返回true，否则返回false
     */
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

    /**
     * 获取当前时间毫秒数
     *
     * @return 当前时间的毫秒数
     */
    private long currentTimeMillis() {
        return clock.millis();
    }
}
