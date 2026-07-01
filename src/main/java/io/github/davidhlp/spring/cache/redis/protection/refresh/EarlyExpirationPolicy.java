package io.github.davidhlp.spring.cache.redis.protection.refresh;

/**
 * 提前过期判定 seam:封装「热点 key 是否应在真正过期前主动刷新」的决策。
 *
 * <p>默认实现 {@link DefaultEarlyExpirationPolicy} 为 Spring {@code @Component};自定义实现声明
 * {@code @Bean} 即可顶替(对齐 {@code TtlPolicy} / {@code NullValuePolicy} / {@code LockManager} /
 * {@code BloomIFilter} 的可替换纪律,落实 ADR-0005「handlers 可替换」长寿对冲)。
 * {@code EarlyExpirationHandler} 依赖本接口而非具体类,使判定可独立测试与替换。
 *
 * <p>ADR-0025 前,本判定寄生在 {@code protection.avalanche.TtlPolicy} 上(唯一消费者是 refresh 域的
 * {@code EarlyExpirationHandler}),且其所需的 {@code Clock} 依赖被一同拖进 avalanche 包。本接口把
 * 判定迁回 refresh 域自有 seam,使 {@code TtlPolicy} 回归纯雪崩(抖动)关注,refresh 与 avalanche
 * 两域依赖方向不再倒置。
 *
 * @see EarlyExpirationHandler
 */
public interface EarlyExpirationPolicy {

    /**
     * 是否应提前刷新(热 key 同步/异步刷新判定):已用时长占比 ≥ {@code 1 - threshold} 时返回 true。
     *
     * @param createdTime 缓存项创建时间戳(毫秒)
     * @param ttlSeconds  缓存项 TTL(秒)
     * @param threshold   提前过期阈值(剩余 TTL 占比,如 0.3 表示剩余 30% 时触发)
     * @return 应提前刷新返回 true,否则 false
     */
    boolean shouldRefresh(long createdTime, long ttlSeconds, double threshold);
}
