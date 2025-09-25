package com.david.spring.cache.redis.event;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.time.Duration;

/**
 * 缓存写入事件
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
public class CachePutEvent extends CacheEvent {
    private Object value;
    private Duration ttl;
    private long executionTime;

    public CachePutEvent(String cacheName, Object cacheKey, String source, Object value, Duration ttl, long executionTime) {
        super(cacheName, cacheKey, source);
        this.value = value;
        this.ttl = ttl;
        this.executionTime = executionTime;
    }

    @Override
    public CacheEventType getEventType() {
        return CacheEventType.CACHE_PUT;
    }
}