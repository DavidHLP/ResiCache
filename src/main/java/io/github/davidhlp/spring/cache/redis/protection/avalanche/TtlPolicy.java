package io.github.davidhlp.spring.cache.redis.protection.avalanche;

import java.time.Duration;

/**
 * TTL 策略 seam:封装 TTL 应用判定、最终值计算(含抖动防雪崩)。
 *
 * <p>默认实现 {@link DefaultTtlPolicy} 由自动配置显式注册;自定义实现声明
 * {@code @Bean} 即可按类型顶替。{@code TtlHandler} 仅依赖本接口。
 *
 * <p>本接口专注雪崩(抖动)关注;提前过期判定由
 * {@code protection.refresh.EarlyExpirationPolicy} 自有 seam 承载。
 */
public interface TtlPolicy {

    /** ttl 非空、非零、非负则应用。 */
    boolean shouldApply(Duration ttl);

    /** 计算最终 TTL;{@code randomTtl=true} 时按 {@code variance} 抖动(防雪崩)。 */
    long calculateFinalTtl(Long baseTtl, boolean randomTtl, float variance);
}
