package io.github.davidhlp.spring.cache.redis.protection.avalanche;

import java.time.Duration;

/**
 * TTL 策略 seam:封装 TTL 应用判定、最终值计算(含抖动防雪崩)、提前过期判定。
 *
 * <p>默认实现 {@link DefaultTtlPolicy} 为 Spring {@code @Component};自定义实现声明
 * {@code @Bean} 即可顶替(对齐 {@code LockManager} / {@code BloomIFilter} 的可替换纪律,
 * 落实 ADR-0005「handlers 可替换」长寿对冲)。{@code TtlHandler} 与
 * {@code EarlyExpirationHandler} 依赖本接口而非具体类,使策略可独立测试与替换。
 *
 * <p>此前 {@code DefaultTtlPolicy} 是无接口的 {@code @Component}(假 seam:被 IoC 管理
 * 却无法顶替)。本接口把它从假 seam 升为真 seam。
 */
public interface TtlPolicy {

    /** ttl 非空、非零、非负则应用。 */
    boolean shouldApply(Duration ttl);

    /** 计算最终 TTL;{@code randomTtl=true} 时按 {@code variance} 抖动(防雪崩)。 */
    long calculateFinalTtl(Long baseTtl, boolean randomTtl, float variance);

    /** 是否应提前过期(热 key 异步刷新判定):已用时长占比 ≥ 1 - threshold。 */
    boolean shouldEarlyExpiration(long createdTime, long ttlSeconds, double threshold);
}
