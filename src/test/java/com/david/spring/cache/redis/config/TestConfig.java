package com.david.spring.cache.redis.config;

import com.david.spring.cache.redis.RedisProCacheWriterTestable;
import com.david.spring.cache.redis.core.writer.WriterChainableUtils;
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
            WriterChainableUtils writerChainableUtils) {
        return new RedisProCacheWriterTestable(
                redisTemplate,
                CacheStatisticsCollector.none(),
                redisCacheRegister,
                writerChainableUtils);
    }
}
