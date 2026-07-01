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
        return instantiateRedisProCache(name, resolveCacheConfiguration(cacheConfiguration));
    }

    @Override
    @Nullable
    protected RedisCache getMissingCache(@NonNull String name) {
        log.debug("Creating missing RedisProCache for cache name: {}", name);
        return instantiateRedisProCache(name, resolveCacheConfiguration(null));
    }

    /**
     * 实例化 {@link RedisProCache} 的单一 seam — 收敛 8 参构造调用的重复样板
     * (ADR-0016,原 {@code createRedisCache} 与 {@code getMissingCache} 各自
     * 8-arg 重复).
     *
     * <p><b>deletion test</b>:删本方法 → {@code createRedisCache} 与
     * {@code getMissingCache} 恢复各自 8-arg 调用;任一参数新增/重命名时两处
     * 必漏改一边(本 manager 是 Spring 扩展点,Spring 框架可能在 vNext 增删参数).
     *
     * <p>参数契约:与 {@link RedisProCache} 8 参构造完全一致;
     * {@link RedisCacheConfiguration} 由调用方在传入前用 {@link #resolveCacheConfiguration}
     * 归一化(可能为 null → fallback 到 {@link #getDefaultCacheConfiguration()}).
     *
     * @param name      缓存名称
     * @param config    已归一化的缓存配置(可为 null,走默认)
     * @return 新建 {@link RedisProCache} 实例
     */
    private RedisProCache instantiateRedisProCache(String name, RedisCacheConfiguration config) {
        return new RedisProCache(
                name,
                redisProCacheWriter,
                config,
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
}
