package com.david.spring.cache.redis;

import com.david.spring.cache.redis.core.writer.RedisProCacheWriter;
import com.david.spring.cache.redis.core.writer.chain.CacheHandlerChainFactory;
import com.david.spring.cache.redis.core.writer.support.TypeSupport;
import com.david.spring.cache.redis.register.RedisCacheRegister;

import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.core.RedisTemplate;

public class RedisProCacheWriterTestable extends RedisProCacheWriter {

    public RedisProCacheWriterTestable(
            RedisTemplate<String, Object> redisTemplate,
            CacheStatisticsCollector statistics,
            RedisCacheRegister redisCacheRegister,
            TypeSupport support,
            CacheHandlerChainFactory cacheHandlerChainFactory) {
        super(
                redisTemplate,
                redisTemplate.opsForValue(),
                statistics,
                redisCacheRegister,
                support,
                cacheHandlerChainFactory);
    }

    @Override
    public long getTtl(String redisKey) {
        return super.getTtl(redisKey);
    }

    @Override
    public long getExpiration(String redisKey) {
        return super.getExpiration(redisKey);
    }
}
