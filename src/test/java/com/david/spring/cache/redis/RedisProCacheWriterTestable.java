package com.david.spring.cache.redis;

import com.david.spring.cache.redis.core.writer.RedisProCacheWriter;
import com.david.spring.cache.redis.core.writer.WriterChainableUtils;
import com.david.spring.cache.redis.register.RedisCacheRegister;

import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.core.RedisTemplate;

public class RedisProCacheWriterTestable extends RedisProCacheWriter {

    public RedisProCacheWriterTestable(
            RedisTemplate<String, Object> redisTemplate,
            CacheStatisticsCollector statistics,
            RedisCacheRegister redisCacheRegister,
            WriterChainableUtils writerChainableUtils) {
        super(redisTemplate, statistics, redisCacheRegister, writerChainableUtils);
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
