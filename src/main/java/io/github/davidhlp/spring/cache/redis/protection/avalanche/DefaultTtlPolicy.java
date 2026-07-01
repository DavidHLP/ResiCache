package io.github.davidhlp.spring.cache.redis.protection.avalanche;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 默认的 TTL 策略:无状态纯 TTL 数学(TTL 应用判定 + 高斯抖动防雪崩)。
 *
 * <p>实现 {@link TtlPolicy} seam;自定义实现声明 {@code @Bean} 可顶替(对齐 LockManager /
 * BloomIFilter 纪律,落实 ADR-0005)。
 *
 * <p>ADR-0025:原 {@code shouldEarlyExpiration} + {@code Clock} 字段(仅为该方法而存在)已迁至
 * {@code protection.refresh.DefaultEarlyExpirationPolicy};本类随之退化为无状态,不再持有 {@code Clock}。
 */
@Component
public class DefaultTtlPolicy implements TtlPolicy {

    /**
     * 判断给定的Duration是否应该应用
     *
     * @param ttl Duration类型的TTL值
     * @return 如果ttl不为null且不为零且不为负数则返回true，否则返回false
     */
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
}
