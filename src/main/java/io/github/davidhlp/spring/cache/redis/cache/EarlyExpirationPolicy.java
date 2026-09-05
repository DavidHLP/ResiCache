package io.github.davidhlp.spring.cache.redis.cache;


/**
 * 提前过期判定 seam:封装「热点 key 是否应在真正过期前主动刷新」的决策。
 *
 * <p>默认实现 {@link DefaultEarlyExpirationPolicy} 由自动配置显式注册;自定义实现声明
 * {@code @Bean} 即可按类型顶替。{@code EarlyExpirationHandler} 仅依赖本接口,
 * 而提交、取消和清理由 internal executor 自己负责。
 *
 * <p>本 seam 属 refresh 域;{@code TtlPolicy} 专注雪崩(抖动),两域依赖方向不倒置。
 *
 * @see EarlyExpirationHandler
 */
interface EarlyExpirationPolicy {

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
