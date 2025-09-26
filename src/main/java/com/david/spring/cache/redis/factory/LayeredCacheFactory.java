package com.david.spring.cache.redis.factory;

import com.david.spring.cache.redis.core.LayeredCache;
import com.david.spring.cache.redis.core.RedisCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.stereotype.Component;

/**
 * 分层缓存工厂实现
 * 创建本地缓存 + Redis缓存的分层缓存
 */
@Slf4j
@Component
public class LayeredCacheFactory implements CacheFactory {

    @Override
    public Cache createCache(CacheCreationConfig config) {
        log.info("Creating layered cache '{}' with TTL: {}", config.getCacheName(), config.getDefaultTtl());

        // 创建本地缓存（一级缓存）
        Cache localCache = new ConcurrentMapCache(config.getCacheName() + "_local", config.isAllowNullValues());

        // 直接创建Redis缓存（二级缓存），避免循环依赖
        Cache redisCache = new RedisCache(
                config.getCacheName(),
                config.getRedisTemplate(),
                config.getDefaultTtl(),
                config.isAllowNullValues()
        );

        return new LayeredCache(config.getCacheName(), localCache, redisCache);
    }

    @Override
    public boolean supports(CacheType cacheType) {
        return CacheType.LAYERED == cacheType;
    }

    @Override
    public int getOrder() {
        return 2; // 分层缓存的优先级
    }
}