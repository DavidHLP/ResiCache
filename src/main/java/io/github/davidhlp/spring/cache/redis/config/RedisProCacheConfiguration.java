package io.github.davidhlp.spring.cache.redis.config;

import io.github.davidhlp.spring.cache.redis.cache.loader.CacheOperationResolver;
import io.github.davidhlp.spring.cache.redis.cache.model.ResiCacheFeatures;
import io.github.davidhlp.spring.cache.redis.cache.RedisProCacheWriter;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChainFactory;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncSupport;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncLockTimeout;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomGate;
import io.github.davidhlp.spring.cache.redis.protection.refresh.ThreadPoolEarlyExpirationExecutor;
import io.github.davidhlp.spring.cache.redis.serialization.TypeSupport;
import io.github.davidhlp.spring.cache.redis.serialization.SecureJacksonRedisSerializer;
import io.github.davidhlp.spring.cache.redis.serialization.SecureJacksonSerializerFactory;
import io.github.davidhlp.spring.cache.redis.cache.RedisProCacheManager;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Clock;
import java.util.Map;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = "io.github.davidhlp.spring.cache.redis")
@EnableConfigurationProperties(RedisProCacheProperties.class)
public class RedisProCacheConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RedisProCacheWriter redisProCacheWriter(
            RedisTemplate<String, Object> redisCacheTemplate,
            TypeSupport typeSupport,
            CacheHandlerChainFactory chainFactory,
            CacheStatisticsCollector cacheStatisticsCollector,
            CacheOperationResolver operationResolver) {
        RedisProCacheWriter writer = new RedisProCacheWriter(
                redisCacheTemplate,
                redisCacheTemplate.opsForValue(),
                cacheStatisticsCollector,
                typeSupport,
                chainFactory,
                operationResolver);
        log.info("Created RedisProCacheWriter with handler chain pattern");
        return writer;
    }

    @Bean
    @ConditionalOnMissingBean(RedisCacheConfiguration.class)
    public RedisCacheConfiguration defaultRedisCacheConfiguration(
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            RedisProCacheProperties properties,
            SecureJacksonSerializerFactory serializerFactory) {
        // 装配走单点 factory,与 RedisConnectionConfiguration 同源。
        SecureJacksonRedisSerializer valueSerializer =
                serializerFactory.create(objectMapper, properties.getSerializer());

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(properties.getDefaultTtl())
                .serializeKeysWith(org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer));

        // 应用全局键前缀
        if (properties.getKeyPrefix() != null && !properties.getKeyPrefix().isEmpty()) {
            config = config.computePrefixWith(cacheName -> properties.getKeyPrefix() + cacheName + "::");
            log.debug("Applied global key prefix: {}", properties.getKeyPrefix());
        }

        log.debug("Created default RedisCacheConfiguration with TTL: {}", properties.getDefaultTtl());
        return config;
    }

    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    public RedisProCacheManager cacheManager(
            RedisProCacheWriter redisProCacheWriter,
            RedisCacheConfiguration defaultRedisCacheConfiguration,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            BloomGate bloomGate,
            CacheOperationResolver operationResolver,
            SyncSupport syncSupport,
            SyncLockTimeout syncLockTimeout,
            RedisProCacheProperties properties) {
        // 构建 per-cache 配置映射
        Map<String, RedisCacheConfiguration> initialCacheConfigurations = buildInitialCacheConfigurations(
                properties, defaultRedisCacheConfiguration);

        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        if (meterRegistry == null) {
            log.debug("MeterRegistry not available — metrics will be disabled");
        }

        ResiCacheFeatures features = ResiCacheFeatures.builder()
                .meterRegistry(meterRegistry)
                .bloomGate(bloomGate)
                .operationResolver(operationResolver)
                .syncSupport(syncSupport)
                .syncLockTimeout(syncLockTimeout)
                .build();

        RedisProCacheManager manager = new RedisProCacheManager(
                redisProCacheWriter,
                defaultRedisCacheConfiguration,
                features,
                initialCacheConfigurations,
                properties.isTransactionAware());
        log.debug("Created RedisProCacheManager with {} initial cache configurations, transactionAware={}",
                initialCacheConfigurations.size(), properties.isTransactionAware());
        return manager;
    }

    /**
     * 根据 properties 中的 caches 配置构建初始缓存配置映射
     */
    private Map<String, RedisCacheConfiguration> buildInitialCacheConfigurations(
            RedisProCacheProperties properties,
            RedisCacheConfiguration defaultConfig) {
        Map<String, RedisCacheConfiguration> result = new java.util.HashMap<>();
        if (properties.getCaches() == null || properties.getCaches().isEmpty()) {
            return result;
        }

        for (Map.Entry<String, RedisProCacheProperties.CacheConfig> entry : properties.getCaches().entrySet()) {
            String cacheName = entry.getKey();
            RedisProCacheProperties.CacheConfig cacheConfig = entry.getValue();
            RedisCacheConfiguration config = defaultConfig;

            // 应用 per-cache TTL
            if (cacheConfig.getTtl() != null) {
                config = config.entryTtl(cacheConfig.getTtl());
            }

            // 应用 per-cache 键前缀
            if (cacheConfig.getKeyPrefix() != null && !cacheConfig.getKeyPrefix().isEmpty()) {
                config = config.computePrefixWith(name -> cacheConfig.getKeyPrefix() + name + "::");
            }

            // 应用 per-cache 空值策略
            if (Boolean.FALSE.equals(cacheConfig.getCacheNullValues())) {
                config = config.disableCachingNullValues();
            }

            result.put(cacheName, config);
            log.debug("Registered initial cache configuration: cacheName={}, ttl={}, keyPrefix={}",
                    cacheName, cacheConfig.getTtl(), cacheConfig.getKeyPrefix());
        }

        return result;
    }

    @Bean
    @ConditionalOnMissingBean(KeyGenerator.class)
    public KeyGenerator keyGenerator() {
        log.debug("Created SimpleKeyGenerator for cache key generation");
        return new SimpleKeyGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheStatisticsCollector cacheStatisticsCollector() {
        return CacheStatisticsCollector.create();
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    /**
     * 提前过期异步刷新执行器:从 {@code resi-cache.early-expiration.*}
     * 读池参数构造。用户可自定义同类型 bean 顶替（{@code @ConditionalOnMissingBean}）。
     */
    @Bean
    @ConditionalOnMissingBean
    public ThreadPoolEarlyExpirationExecutor earlyExpirationExecutor(
            RedisProCacheProperties properties,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        RedisProCacheProperties.EarlyExpirationProperties ee = properties.getEarlyExpiration();
        log.info("Creating ThreadPoolEarlyExpirationExecutor: core={}, max={}, queue={}",
                ee.getPoolSize(), ee.getMaxPoolSize(), ee.getQueueCapacity());
        return new ThreadPoolEarlyExpirationExecutor(
                ee.getPoolSize(),
                ee.getMaxPoolSize(),
                ee.getQueueCapacity(),
                meterRegistryProvider.getIfAvailable());
    }
}

