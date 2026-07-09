package io.github.davidhlp.spring.cache.redis.cache;

import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Map;

@Slf4j
public class RedisProCacheManager extends RedisCacheManager {

    private final RedisProCacheWriter redisProCacheWriter;
    private final RedisCacheConfiguration defaultConfiguration;
    private final ResiCacheFeatures features;

    /**
     * 构造 ResiCacheManager 实例 — Round 5 / ADR-0014 收敛后的唯一构造入口.
     *
     * <p><b>单一 seam</b>:全部可选特性收口到 {@link ResiCacheFeatures} 值对象(取代原
     * 4 个位置可空参数 meterRegistry/bloomSupport/operationResolver/syncSupport),
     * 「null = 该特性禁用」的契约只存在于 {@link ResiCacheFeatures} 一处。
     *
     * <p><b>参数契约</b>:
     * <ul>
     *   <li>{@code cacheWriter / defaultCacheConfiguration} —— 必传,转发给
     *       {@code RedisCacheManager.super(...)}</li>
     *   <li>{@code features} —— 可选特性集合(见 {@link ResiCacheFeatures}),透传给
     *       每个 {@link RedisProCache}</li>
     *   <li>{@code initialCacheConfigurations} —— 必传(默认空 map,允许所有 cacheName 走默认配置)</li>
     *   <li>{@code transactionAware} —— 必传(默认 {@code false})</li>
     * </ul>
     */
    public RedisProCacheManager(
            RedisProCacheWriter cacheWriter,
            RedisCacheConfiguration defaultCacheConfiguration,
            ResiCacheFeatures features,
            Map<String, RedisCacheConfiguration> initialCacheConfigurations,
            boolean transactionAware) {
        super(cacheWriter, defaultCacheConfiguration, true, initialCacheConfigurations);
        this.redisProCacheWriter = cacheWriter;
        this.defaultConfiguration = defaultCacheConfiguration;
        this.features = features;
        setTransactionAware(transactionAware);
    }

    @Override
    @NonNull
    protected RedisCache createRedisCache(
            @NonNull String name, RedisCacheConfiguration cacheConfiguration) {
        log.debug("Creating RedisProCache for cache name: {}", name);
        return instantiateRedisProCache(name, resolveCacheConfiguration(cacheConfiguration));
    }

    @Override
    @Nullable
    protected RedisCache getMissingCache(@NonNull String name) {
        log.debug("Creating missing RedisProCache for cache name: {}", name);
        return instantiateRedisProCache(name, resolveCacheConfiguration(null));
    }

    /**
     * 实例化 {@link RedisProCache} 的单一 seam — 收敛两个 Spring 扩展点回调的重复样板
     * (ADR-0016,原 {@code createRedisCache} 与 {@code getMissingCache} 各自重复).
     *
     * @param name   缓存名称
     * @param config 已归一化的缓存配置(可为 null,走默认)
     * @return 新建 {@link RedisProCache} 实例
     */
    private RedisProCache instantiateRedisProCache(String name, RedisCacheConfiguration config) {
        return new RedisProCache(name, redisProCacheWriter, config, features);
    }

    private RedisCacheConfiguration resolveCacheConfiguration(
            @Nullable RedisCacheConfiguration cacheConfiguration) {
        return cacheConfiguration != null ? cacheConfiguration : getDefaultCacheConfiguration();
    }
}
