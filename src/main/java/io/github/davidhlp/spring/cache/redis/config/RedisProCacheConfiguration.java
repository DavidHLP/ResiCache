package io.github.davidhlp.spring.cache.redis.config;

import io.github.davidhlp.spring.cache.redis.annotation.handler.AnnotationChainEngine;
import io.github.davidhlp.spring.cache.redis.annotation.handler.CachePutAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.annotation.handler.CacheableAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.annotation.handler.CachingAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.annotation.handler.EvictAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.cache.RedisProCacheManager;
import io.github.davidhlp.spring.cache.redis.cache.RedisProCacheWriter;
import io.github.davidhlp.spring.cache.redis.cache.loader.CacheOperationResolver;
import io.github.davidhlp.spring.cache.redis.cache.model.ResiCacheFeatures;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChain;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChainFactory;
import io.github.davidhlp.spring.cache.redis.chain.ChainEngine;
import io.github.davidhlp.spring.cache.redis.chain.handler.ActualCacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.handler.CacheErrorHandler;
import io.github.davidhlp.spring.cache.redis.chain.metadata.DefaultMethodMetadataResolver;
import io.github.davidhlp.spring.cache.redis.chain.metadata.MethodMetadataResolver;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheAttributesProjector;
import io.github.davidhlp.spring.cache.redis.operation.SpringCacheableAdapter;
import io.github.davidhlp.spring.cache.redis.protection.avalanche.DefaultTtlPolicy;
import io.github.davidhlp.spring.cache.redis.protection.avalanche.TtlHandler;
import io.github.davidhlp.spring.cache.redis.protection.avalanche.TtlPolicy;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomFilterConfig;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomFilterHandler;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomGate;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomHashStrategy;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomRebuilder;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomSupport;
import io.github.davidhlp.spring.cache.redis.protection.bloom.MessageDigestBloomHashStrategy;
import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.BloomIFilter;
import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.HierarchicalBloomIFilter;
import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.LocalBloomIFilter;
import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.RedisBloomIFilter;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncLockHandler;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncLockTimeout;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncSupport;
import io.github.davidhlp.spring.cache.redis.protection.nullvalue.DefaultNullValuePolicy;
import io.github.davidhlp.spring.cache.redis.protection.nullvalue.NullValueEncoder;
import io.github.davidhlp.spring.cache.redis.protection.nullvalue.NullValueHandler;
import io.github.davidhlp.spring.cache.redis.protection.nullvalue.NullValuePolicy;
import io.github.davidhlp.spring.cache.redis.protection.refresh.DefaultEarlyExpirationPolicy;
import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationHandler;
import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationPolicy;
import io.github.davidhlp.spring.cache.redis.protection.refresh.ThreadPoolEarlyExpirationExecutor;
import io.github.davidhlp.spring.cache.redis.serialization.SecureJacksonRedisSerializer;
import io.github.davidhlp.spring.cache.redis.serialization.SecureJacksonSerializerFactory;
import io.github.davidhlp.spring.cache.redis.serialization.TypeSupport;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;


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
        BloomRebuilder.class,
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
public class RedisProCacheConfiguration {

    @Bean
    @ConditionalOnMissingBean(MethodMetadataResolver.class)
    public MethodMetadataResolver methodMetadataResolver() {
        return new DefaultMethodMetadataResolver();
    }

    @Bean
    @ConditionalOnMissingBean(CacheErrorHandler.class)
    public CacheErrorHandler cacheErrorHandler() {
        return new CacheErrorHandler();
    }

    @Bean
    @ConditionalOnMissingBean(CacheOperationResolver.class)
    public CacheOperationResolver cacheOperationResolver(
            MethodMetadataResolver methodMetadataResolver,
            io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister register) {
        return new CacheOperationResolver(methodMetadataResolver, register);
    }

    @Bean
    @ConditionalOnMissingBean(BloomFilterConfig.class)
    public BloomFilterConfig bloomFilterConfig(
            @Value("${resi-cache.bloom.prefix:bf:}") String keyPrefix,
            @Value("${resi-cache.bloom.bit-size:8388608}") int bitSize,
            @Value("${resi-cache.bloom.hash-functions:3}") int hashFunctions,
            @Value("${resi-cache.bloom.hash-cache-size:10000}") int hashCacheSize) {
        return new BloomFilterConfig(keyPrefix, bitSize, hashFunctions, hashCacheSize);
    }

    @Bean
    @ConditionalOnMissingBean(BloomHashStrategy.class)
    public BloomHashStrategy bloomHashStrategy() {
        return new MessageDigestBloomHashStrategy();
    }

    @Bean
    @ConditionalOnMissingBean(TtlPolicy.class)
    public TtlPolicy ttlPolicy() {
        return new DefaultTtlPolicy();
    }

    @Bean
    @ConditionalOnMissingBean(NullValuePolicy.class)
    public NullValuePolicy nullValuePolicy(NullValueEncoder encoder) {
        return new DefaultNullValuePolicy(encoder);
    }

    @Bean
    @ConditionalOnMissingBean(EarlyExpirationPolicy.class)
    public EarlyExpirationPolicy earlyExpirationPolicy(Clock clock) {
        return new DefaultEarlyExpirationPolicy(clock);
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
            BloomHashStrategy hashStrategy,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        LocalBloomIFilter local = new LocalBloomIFilter(config, hashStrategy);
        RedisBloomIFilter remote = new RedisBloomIFilter(
                redisTemplate, config, hashStrategy, meterRegistryProvider.getIfAvailable());
        remote.init();
        return new HierarchicalBloomIFilter(local, remote);
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisProCacheWriter redisProCacheWriter(
            @Qualifier("redisCacheTemplate") RedisTemplate<String, Object> redisCacheTemplate,
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
                meterRegistryProvider.getIfAvailable());
    }
}

