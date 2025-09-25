package com.david.spring.cache.redis.event;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

/**
 * 缓存命中事件
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
public class CacheHitEvent extends CacheEvent {
    private Object value;
    private long accessTime;

    public CacheHitEvent(String cacheName, Object cacheKey, String source, Object value, long accessTime) {
        super(cacheName, cacheKey, source);
        this.value = value;
        this.accessTime = accessTime;
    }

    @Override
    public CacheEventType getEventType() {
        return CacheEventType.CACHE_HIT;
    }
}