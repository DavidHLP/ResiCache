package com.david.spring.cache.redis.event;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

/**
 * 缓存未命中事件
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
public class CacheMissEvent extends CacheEvent {
    private String reason;

    public CacheMissEvent(String cacheName, Object cacheKey, String source, String reason) {
        super(cacheName, cacheKey, source);
        this.reason = reason;
    }

    @Override
    public CacheEventType getEventType() {
        return CacheEventType.CACHE_MISS;
    }
}