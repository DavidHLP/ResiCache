package io.github.davidhlp.spring.cache.redis.protection.avalanche;

import java.time.Duration;

/**
 * TTL 策略 seam:封装 TTL 应用判定、最终值计算(含抖动防雪崩)。
 *
 * <p>默认实现 {@link DefaultTtlPolicy} 为 Spring {@code @Component};自定义实现声明
 * {@code @Bean} 即可顶替(对齐 {@code LockManager} / {@code BloomIFilter} 的可替换纪律,
 * 落实 ADR-0005「handlers 可替换」长寿对冲)。{@code TtlHandler} 依赖本接口而非具体类,
 * 使策略可独立测试与替换。
 *
 * <p>此前 {@code DefaultTtlPolicy} 是无接口的 {@code @Component}(假 seam:被 IoC 管理
 * 却无法顶替)。本接口把它从假 seam 升为真 seam。
 *
 * <p>ADR-0025:本接口原第三方法 {@code shouldEarlyExpiration}(提前过期判定)唯一消费者是
 * refresh 域的 {@code EarlyExpirationHandler},已迁至 {@code protection.refresh.EarlyExpirationPolicy}
 * 自有 seam。本接口回归纯雪崩(抖动)关注,不再跨域承载 refresh 决策。
 */
public interface TtlPolicy {

    /** ttl 非空、非零、非负则应用。 */
    boolean shouldApply(Duration ttl);

    /** 计算最终 TTL;{@code randomTtl=true} 时按 {@code variance} 抖动(防雪崩)。 */
    long calculateFinalTtl(Long baseTtl, boolean randomTtl, float variance);
}
