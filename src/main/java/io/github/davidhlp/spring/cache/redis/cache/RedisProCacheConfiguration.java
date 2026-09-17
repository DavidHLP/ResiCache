package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.BloomIFilter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Slf4j
@Configuration(proxyBeanMethods = false)
@Import({
        AnnotationChainEngine.class,
        CachePutAnnotationHandler.class,
        CacheableAnnotationHandler.class,
        CachingAnnotationHandler.class,
        EvictAnnotationHandler.class,
        RedisCacheAttributesProjector.class,
        SpringCacheableAdapter.class,
        CacheHandlerChain.class,
        CacheHandlerChainFactory.class,
        ChainEngine.class,
        ActualCacheHandler.class,
        TtlHandler.class,
        NullValueEncoder.class,
        NullValueHandler.class,
        BloomFilterHandler.class,
        BloomGate.class,
        BloomSupport.class,
        SyncLockHandler.class,
        SyncLockTimeout.class,
        SyncSupport.class,
        EarlyExpirationHandler.class,
        SecureJacksonSerializerFactory.class,
        TypeSupport.class,
        SerializationPreFlightProbe.class,
        SerializerWhitelistStartupGuard.class,
        TlsConfigurationValidator.class
})
@EnableConfigurationProperties(RedisProCacheProperties.class)
class RedisProCacheConfiguration {

    /**
     * 标准 ChainObserver beans — P1-API-001-C:标准和用户 observer 均为有序 Bean,
     * 由 {@link CacheHandlerChainFactory} 单一装配点注入 Engine。
     *
     * <p>顺序(MDC → DebugLog → Timer → FiredCounter)由 {@code @Order} 显式声明:
     * MDC 先 stamp,DEBUG log 再读 requestId,Timer/FiredCounter 最后打点。
     * registry 缺失时 Timer/FiredCounter observer 内部 no-op。
     */
    @Bean
    @org.springframework.core.annotation.Order(1)
    public io.github.davidhlp.spring.cache.redis.cache.MDCStampChainObserver mdcStampChainObserver() {
        return new io.github.davidhlp.spring.cache.redis.cache.MDCStampChainObserver();
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    public io.github.davidhlp.spring.cache.redis.cache.ChainDebugLogChainObserver chainDebugLogChainObserver() {
        return new io.github.davidhlp.spring.cache.redis.cache.ChainDebugLogChainObserver();
    }

    @Bean
    @org.springframework.core.annotation.Order(3)
    public io.github.davidhlp.spring.cache.redis.cache.ChainTimerChainObserver chainTimerChainObserver(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            RedisProCacheProperties properties) {
        return new io.github.davidhlp.spring.cache.redis.cache.ChainTimerChainObserver(
                metricsRegistry(meterRegistryProvider, properties));
    }

    @Bean
    @org.springframework.core.annotation.Order(4)
    public io.github.davidhlp.spring.cache.redis.cache.FiredCounterChainObserver firedCounterChainObserver(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            RedisProCacheProperties properties) {
        return new io.github.davidhlp.spring.cache.redis.cache.FiredCounterChainObserver(
                metricsRegistry(meterRegistryProvider, properties));
    }

    @Bean
    @ConditionalOnMissingBean(MethodMetadataResolver.class)
    public MethodMetadataResolver methodMetadataResolver() {
        return new DefaultMethodMetadataResolver();
    }

    @Bean
    @ConditionalOnMissingBean(CacheErrorHandler.class)
    public CacheErrorHandler cacheErrorHandler(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            RedisProCacheProperties properties) {
        MeterRegistry registry = metricsRegistry(meterRegistryProvider, properties);
        // ADR-06:统一失败指标 reporter(registry 缺失 → 内部 no-op)
        return new CacheErrorHandler(
                registry == null ? null
                        : new io.github.davidhlp.spring.cache.redis.cache.CacheFailureReporter(registry));
    }

    @Bean
    @ConditionalOnMissingBean(CacheOperationResolver.class)
    public CacheOperationResolver cacheOperationResolver(
            MethodMetadataResolver methodMetadataResolver,
            io.github.davidhlp.spring.cache.redis.cache.RedisCacheRegister register) {
        return new CacheOperationResolver(methodMetadataResolver, register);
    }

    @Bean
    @ConditionalOnMissingBean(BloomFilterConfig.class)
    public BloomFilterConfig bloomFilterConfig(RedisProCacheProperties properties) {
        // P1-CONFIG-001:布隆参数收口到 properties 统一模型(绑定期已校验),
        // 取代散读 @Value(绕过统一校验模型)。
        RedisProCacheProperties.BloomProperties bloom = properties.getBloom();
        return new BloomFilterConfig(
                bloom.getPrefix(),
                bloom.getBitSize(),
                bloom.getHashFunctions(),
                bloom.getHashCacheSize());
    }


    /**
     * Default Bloom implementation is one explicitly composed adapter. A user
     * supplied BloomIFilter replaces the entire composition by type.
     */
    @Bean
    @ConditionalOnMissingBean(BloomIFilter.class)
    public BloomIFilter bloomIFilter(
            @Qualifier("redisCacheTemplate") RedisTemplate<String, Object> redisTemplate,
            BloomFilterConfig config,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            RedisProCacheProperties properties) {
        LocalBloomIFilter local = new LocalBloomIFilter(config);
        RedisBloomIFilter remote = new RedisBloomIFilter(
                redisTemplate, config, metricsRegistry(meterRegistryProvider, properties));
        remote.init();
        return new HierarchicalBloomIFilter(local, remote);
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisProCacheWriter redisProCacheWriter(
            TypeSupport typeSupport,
            CacheHandlerChainFactory chainFactory,
            CacheStatisticsCollector cacheStatisticsCollector,
            CacheOperationResolver operationResolver) {
        RedisProCacheWriter writer = new RedisProCacheWriter(
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
        SecureJacksonRedisSerializer valueSerializer =
                serializerFactory.create(objectMapper, properties.getSerializer());

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(properties.getDefaultTtl())
                .serializeKeysWith(org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer));

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
        Map<String, RedisCacheConfiguration> initialCacheConfigurations =
                buildInitialCacheConfigurations(properties, defaultRedisCacheConfiguration);

        MeterRegistry meterRegistry = metricsRegistry(meterRegistryProvider, properties);
        if (meterRegistry == null) {
            log.debug("MeterRegistry not available or metrics disabled — metrics will be disabled");
        }

        ResiCacheFeatures features = ResiCacheFeatures.builder()
                .meterRegistry(meterRegistry)
                .bloomGate(bloomGate)
                .operationResolver(operationResolver)
                .syncSupport(syncSupport)
                .syncLockTimeout(syncLockTimeout)
                .build();

        return new RedisProCacheManager(
                redisProCacheWriter,
                defaultRedisCacheConfiguration,
                features,
                initialCacheConfigurations,
                properties.isTransactionAware());
    }

    private Map<String, RedisCacheConfiguration> buildInitialCacheConfigurations(
            RedisProCacheProperties properties,
            RedisCacheConfiguration defaultConfig) {
        Map<String, RedisCacheConfiguration> result = new HashMap<>();
        if (properties.getCaches() == null || properties.getCaches().isEmpty()) {
            return result;
        }

        for (Map.Entry<String, RedisProCacheProperties.CacheConfig> entry
                : properties.getCaches().entrySet()) {
            String cacheName = entry.getKey();
            RedisProCacheProperties.CacheConfig cacheConfig = entry.getValue();
            RedisCacheConfiguration config = defaultConfig;

            if (cacheConfig.getTtl() != null) {
                config = config.entryTtl(cacheConfig.getTtl());
            }
            if (cacheConfig.getKeyPrefix() != null && !cacheConfig.getKeyPrefix().isEmpty()) {
                config = config.computePrefixWith(name -> cacheConfig.getKeyPrefix() + name + "::");
            }
            if (Boolean.FALSE.equals(cacheConfig.getCacheNullValues())) {
                config = config.disableCachingNullValues();
            }

            result.put(cacheName, config);
        }
        return result;
    }

    @Bean
    @ConditionalOnMissingBean(KeyGenerator.class)
    public KeyGenerator keyGenerator() {
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

    @Bean
    @ConditionalOnMissingBean(ThreadPoolEarlyExpirationExecutor.class)
    public ThreadPoolEarlyExpirationExecutor earlyExpirationExecutor(
            RedisProCacheProperties properties,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        RedisProCacheProperties.EarlyExpirationProperties ee = properties.getEarlyExpiration();
        return new ThreadPoolEarlyExpirationExecutor(
                ee.getPoolSize(),
                ee.getMaxPoolSize(),
                ee.getQueueCapacity(),
                metricsRegistry(meterRegistryProvider, properties));
    }

    static MeterRegistry metricsRegistry(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            RedisProCacheProperties properties) {
        if (properties != null && properties.getMetrics() != null
                && !properties.getMetrics().isEnabled()) {
            return null;
        }
        return meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
    }
}
