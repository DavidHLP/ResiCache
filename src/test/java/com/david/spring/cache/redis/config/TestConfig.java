package com.david.spring.cache.redis.config;

import com.david.spring.cache.redis.RedisProCacheWriterTestable;
import com.david.spring.cache.redis.core.writer.handler.CacheHandlerChainFactory;
import com.david.spring.cache.redis.core.writer.support.TypeSupport;
import com.david.spring.cache.redis.register.RedisCacheRegister;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.core.RedisTemplate;

@TestConfiguration
public class TestConfig {
    @Bean
    public RedisProCacheWriterTestable redisProCacheWriterTestable(
            RedisTemplate<String, Object> redisTemplate,
            RedisCacheRegister redisCacheRegister,
            TypeSupport support,
            CacheHandlerChainFactory cacheHandlerChainFactory,
            CacheStatisticsCollector cacheStatisticsCollector) {
        return new RedisProCacheWriterTestable(
                redisTemplate,
                cacheStatisticsCollector,
                redisCacheRegister,
                support,
                cacheHandlerChainFactory);
    }
}
