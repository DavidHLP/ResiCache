package com.david.spring.cache.redis.config;

import com.david.spring.cache.redis.core.writer.RedisProCacheWriter;
import com.david.spring.cache.redis.core.writer.chain.CacheHandlerChainFactory;
import com.david.spring.cache.redis.core.writer.support.TypeSupport;
import com.david.spring.cache.redis.manager.RedisProCacheManager;
import com.david.spring.cache.redis.register.RedisCacheRegister;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class RedisProCacheConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RedisProCacheWriter redisProCacheWriter(
            RedisTemplate<String, Object> redisCacheTemplate,
            RedisCacheRegister redisCacheRegister,
            TypeSupport typeSupport,
            CacheHandlerChainFactory chainFactory,
            CacheStatisticsCollector cacheStatisticsCollector) {
        RedisProCacheWriter writer =
                new RedisProCacheWriter(
                        redisCacheTemplate,
                        redisCacheTemplate.opsForValue(),
                        cacheStatisticsCollector,
                        redisCacheRegister,
                        typeSupport,
                        chainFactory);
        log.info("Created RedisProCacheWriter with handler chain pattern");
        return writer;
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisCacheConfiguration defaultRedisCacheConfiguration(
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        RedisCacheConfiguration config =
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofMinutes(30))
                        .serializeKeysWith(
                                org.springframework.data.redis.serializer.RedisSerializationContext
                                        .SerializationPair.fromSerializer(
                                        new StringRedisSerializer()))
                        .serializeValuesWith(
                                org.springframework.data.redis.serializer.RedisSerializationContext
                                        .SerializationPair.fromSerializer(
                                        new GenericJackson2JsonRedisSerializer(objectMapper)));

        log.debug("Created default RedisCacheConfiguration with 30 minutes TTL");
        return config;
    }

    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    public RedisProCacheManager cacheManager(
            RedisProCacheWriter redisProCacheWriter,
            RedisCacheConfiguration defaultRedisCacheConfiguration) {
        RedisProCacheManager manager =
                new RedisProCacheManager(redisProCacheWriter, defaultRedisCacheConfiguration);
        log.debug("Created RedisProCacheManager");
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    public KeyGenerator keyGenerator() {
        log.debug("Created SimpleKeyGenerator for cache key generation");
        return new SimpleKeyGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheStatisticsCollector cacheStatisticsCollector() {
        return CacheStatisticsCollector.create();
    }
}
