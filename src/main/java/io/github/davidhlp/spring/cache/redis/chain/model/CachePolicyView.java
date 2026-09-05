package io.github.davidhlp.spring.cache.redis.chain.model;

/**
 * 方法级缓存策略的稳定不可变视图 — handler 可读的最小 policy 面。
 *
 * <p>P1-API-001-B:取代 {@code CacheContext.getCacheOperation()} 对内部
 * {@code RedisCacheableOperation} 的泄漏。稳定扩展 {@link io.github.davidhlp.spring.cache.redis.chain.CacheHandler}
 * 只应读取本视图承载的有限策略字段,不依赖内部 operation 类型。
 *
 * @param ttl                     方法级 TTL 秒数;{@code 0} = 未配置(走参数/默认 TTL)
 * @param randomTtl               是否启用 TTL 随机化(防雪崩)
 * @param variance                TTL 随机化范围
 * @param useBloomFilter          是否启用布隆穿透防护
 * @param sync                    是否启用分布式锁 single-flight(防击穿)
 * @param syncTimeoutSeconds     分布式锁超时秒数(注解 syncTimeout;0 = 默认)
 * @param cacheNullValues         是否缓存 null 值(防穿透)
 * @param enableEarlyExpiration   是否启用提前过期刷新
 * @param earlyExpirationThreshold 提前过期触发阈值(剩余 TTL 占比)
 * @param earlyExpirationMode     提前过期执行模式(同步/异步)
 */
public record CachePolicyView(
        long ttl,
        boolean randomTtl,
        float variance,
        boolean useBloomFilter,
        boolean sync,
        long syncTimeoutSeconds,
        boolean cacheNullValues,
        boolean enableEarlyExpiration,
        double earlyExpirationThreshold,
        io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode earlyExpirationMode) {

    /** Internal adapter contract implemented by cache operation models. */
    public interface Source {
        long getTtl();
        boolean isRandomTtl();
        float getVariance();
        boolean isUseBloomFilter();
        boolean isSync();
        long getSyncTimeout();
        boolean isCacheNullValues();
        boolean isEnableEarlyExpiration();
        double getEarlyExpirationThreshold();
        io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode getEarlyExpirationMode();
    }

    /** 无方法级策略(operation 为 null / 未启用增强)的空视图 — 所有字段取默认。 */
    public static final CachePolicyView NONE = new CachePolicyView(
            0, false, 0f, false, false, 0, false, false, 0.3,
            io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode.SYNC);
}
