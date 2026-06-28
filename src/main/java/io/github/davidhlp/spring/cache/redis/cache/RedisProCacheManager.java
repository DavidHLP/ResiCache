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

import java.util.Collections;
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

    public RedisProCacheManager(
            RedisProCacheWriter cacheWriter,
            RedisCacheConfiguration defaultCacheConfiguration,
            MeterRegistry meterRegistry) {
        this(cacheWriter, defaultCacheConfiguration, meterRegistry, null, null, null, null,
                Collections.emptyMap(), false);
    }

    public RedisProCacheManager(
            RedisProCacheWriter cacheWriter,
            RedisCacheConfiguration defaultCacheConfiguration,
            MeterRegistry meterRegistry,
            BloomSupport bloomSupport,
            RedisCacheRegister redisCacheRegister) {
        this(cacheWriter, defaultCacheConfiguration, meterRegistry, bloomSupport, redisCacheRegister, null, null,
                Collections.emptyMap(), false);
    }

    public RedisProCacheManager(
            RedisProCacheWriter cacheWriter,
            RedisCacheConfiguration defaultCacheConfiguration,
            MeterRegistry meterRegistry,
            BloomSupport bloomSupport,
            RedisCacheRegister redisCacheRegister,
            SyncSupport syncSupport) {
        this(cacheWriter, defaultCacheConfiguration, meterRegistry, bloomSupport, redisCacheRegister, syncSupport, null,
                Collections.emptyMap(), false);
    }

    public RedisProCacheManager(
            RedisProCacheWriter cacheWriter,
            RedisCacheConfiguration defaultCacheConfiguration,
            MeterRegistry meterRegistry,
            BloomSupport bloomSupport,
            RedisCacheRegister redisCacheRegister,
            SyncSupport syncSupport,
            Map<String, RedisCacheConfiguration> initialCacheConfigurations,
            boolean transactionAware) {
        this(cacheWriter, defaultCacheConfiguration, meterRegistry, bloomSupport, redisCacheRegister, syncSupport, null,
                initialCacheConfigurations, transactionAware);
    }

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
