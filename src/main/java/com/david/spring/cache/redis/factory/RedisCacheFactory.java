package com.david.spring.cache.redis.factory;

import com.david.spring.cache.redis.core.RedisCache;
import com.david.spring.cache.redis.event.CacheEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.stereotype.Component;

/**
 * Redis缓存工厂实现
 */
@Slf4j
@Component
public class RedisCacheFactory implements CacheFactory {

    @Autowired(required = false)
    private CacheEventPublisher eventPublisher;

    @Override
    public Cache createCache(CacheCreationConfig config) {
        log.info("Creating Redis cache '{}' with TTL: {}", config.getCacheName(), config.getDefaultTtl());

        RedisCache cache = new RedisCache(
                config.getCacheName(),
                config.getRedisTemplate(),
                config.getDefaultTtl(),
                config.isAllowNullValues()
        );

        // 如果启用了事件发布，则为缓存设置事件发布器
        if (config.isEnableStatistics() && eventPublisher != null) {
            // 这里可以扩展 RedisCache 来支持事件发布
            log.debug("Statistics enabled for cache '{}'", config.getCacheName());
        }

        return cache;
    }

    @Override
    public boolean supports(CacheType cacheType) {
        return CacheType.REDIS == cacheType;
    }

    @Override
    public int getOrder() {
        return 1; // 标准Redis缓存的优先级
    }
}