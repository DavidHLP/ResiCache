package com.david.spring.cache.redis.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.david.spring.cache.redis.core.writer.RedisProCacheWriter;
import com.david.spring.cache.redis.manager.RedisProCacheManager;

import java.time.Duration;

/**
 * Redis缓存核心组件配置
 * 负责：
 * 1. 缓存管理器配置
 * 2. 缓存写入器配置
 * 3. 缓存默认配置策略
 * 4. 键生成器配置
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class RedisCacheConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RedisProCacheWriter redisProCacheWriter(RedisTemplate<String, Object> redisCacheTemplate) {
        RedisProCacheWriter writer = new RedisProCacheWriter(
                redisCacheTemplate,
                org.springframework.data.redis.cache.CacheStatisticsCollector.none()
        );
        log.debug("Created RedisProCacheWriter with custom CachedValue support");
        return writer;
    }

    @Bean
    @ConditionalOnMissingBean
    public org.springframework.data.redis.cache.RedisCacheConfiguration defaultRedisCacheConfiguration() {
        org.springframework.data.redis.cache.RedisCacheConfiguration config =
                org.springframework.data.redis.cache.RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofMinutes(30))
                        .serializeKeysWith(org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                        .serializeValuesWith(org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair
                                .fromSerializer(new GenericJackson2JsonRedisSerializer()));

        log.debug("Created default RedisCacheConfiguration with 30 minutes TTL");
        return config;
    }

    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    public RedisProCacheManager cacheManager(
            RedisProCacheWriter redisProCacheWriter,
            org.springframework.data.redis.cache.RedisCacheConfiguration defaultRedisCacheConfiguration) {
        RedisProCacheManager manager = new RedisProCacheManager(redisProCacheWriter, defaultRedisCacheConfiguration);
        log.debug("Created RedisProCacheManager with custom cache writer");
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    public KeyGenerator keyGenerator() {
        log.debug("Created SimpleKeyGenerator for cache key generation");
        return new SimpleKeyGenerator();
    }
}