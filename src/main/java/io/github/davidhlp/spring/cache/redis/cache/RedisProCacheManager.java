package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.MethodMetadataResolver;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncSupport;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomSupport;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;

import io.micrometer.core.instrument.MeterRegistry;
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
    private final MeterRegistry meterRegistry;
    private final BloomSupport bloomSupport;
    private final RedisCacheRegister redisCacheRegister;
    private final SyncSupport syncSupport;
    private final MethodMetadataResolver methodMetadataResolver;

    /**
     * 构造 ResiCacheManager 实例 — Round 5 / ADR-0014 收敛后的唯一构造入口.
     *
     * <p><b>单一 seam</b>:全部 9 个依赖以命名参数显式传入,无任何构造重载。
     * 调用方需传递 {@code null} 表示"该特性未启用"。
     *
     * <p><b>为什么不做"便利重载"</b>:
     * <ul>
     *   <li>5 个构造重载 = 5 套参数子集 = 调用方必须记住"哪个用哪个" = 接口与实现等宽
     *       (浅模块)</li>
     *   <li>{@code initialCacheConfigurations} + {@code transactionAware} 是 Spring
     *       {@code RedisCacheManager} 的核心构造参数,不应被"便利重载"省略</li>
     *   <li>Spring 装配路径已稳定,生产仅 1 个 9 参构造,3-参重载从未被生产代码使用
     *       (仅 1 个测试使用,见 ADR-0014)</li>
     * </ul>
     *
     * <p><b>参数契约</b>:
     * <ul>
     *   <li>{@code cacheWriter / defaultCacheConfiguration} —— 必传,转发给
     *       {@code RedisCacheManager.super(...)}</li>
     *   <li>{@code meterRegistry} —— 可为 null(生成的 {@link RedisProCache} 计时器为 null,
     *       null-safe 路径生效)</li>
     *   <li>{@code bloomSupport / redisCacheRegister / syncSupport /
     *       methodMetadataResolver} —— 可为 null(关闭对应特性)</li>
     *   <li>{@code initialCacheConfigurations} —— 必传(默认空 map 即
     *       {@code Collections.emptyMap()},允许所有 cacheName 走默认配置)</li>
     *   <li>{@code transactionAware} —— 必传(默认 {@code false} 即可,需要事务支持
     *       时用户通过配置显式开启)</li>
     * </ul>
     */
    public RedisProCacheManager(
            RedisProCacheWriter cacheWriter,
            RedisCacheConfiguration defaultCacheConfiguration,
            MeterRegistry meterRegistry,
            BloomSupport bloomSupport,
            RedisCacheRegister redisCacheRegister,
            SyncSupport syncSupport,
            MethodMetadataResolver methodMetadataResolver,
            Map<String, RedisCacheConfiguration> initialCacheConfigurations,
            boolean transactionAware) {
        super(cacheWriter, defaultCacheConfiguration, true, initialCacheConfigurations);
        this.redisProCacheWriter = cacheWriter;
        this.defaultConfiguration = defaultCacheConfiguration;
        this.meterRegistry = meterRegistry;
        this.bloomSupport = bloomSupport;
        this.redisCacheRegister = redisCacheRegister;
        this.syncSupport = syncSupport;
        this.methodMetadataResolver = methodMetadataResolver;
        setTransactionAware(transactionAware);
    }
    @Override
    @NonNull
    protected RedisCache createRedisCache(
            @NonNull String name, RedisCacheConfiguration cacheConfiguration) {
        log.debug("Creating RedisProCache for cache name: {}", name);
        return new RedisProCache(
                name,
                redisProCacheWriter,
                resolveCacheConfiguration(cacheConfiguration),
                meterRegistry,
                bloomSupport,
                redisCacheRegister,
                syncSupport,
                methodMetadataResolver);
    }

    private RedisCacheConfiguration resolveCacheConfiguration(
            @Nullable RedisCacheConfiguration cacheConfiguration) {
        return cacheConfiguration != null ? cacheConfiguration : getDefaultCacheConfiguration();
    }

    @Override
    @Nullable
    protected RedisCache getMissingCache(@NonNull String name) {
        log.debug("Creating missing RedisProCache for cache name: {}", name);
        return new RedisProCache(
                name,
                redisProCacheWriter,
                resolveCacheConfiguration(null),
                meterRegistry,
                bloomSupport,
                redisCacheRegister,
                syncSupport,
                methodMetadataResolver);
    }
}
