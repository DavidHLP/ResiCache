package com.david.spring.cache.redis.event.entity;

import com.david.spring.cache.redis.event.CacheEvent;
import com.david.spring.cache.redis.event.CacheEventType;
import lombok.Getter;

/**
 * 缓存错误事件
 */
@Getter
public class CacheErrorEvent extends CacheEvent {
    private final Exception exception;
    private final String operation;
    private final long errorTime;

    public CacheErrorEvent(String cacheName, Object cacheKey, String source,
                          Exception exception, String operation) {
        super(cacheName, cacheKey, source);
        this.exception = exception;
        this.operation = operation;
        this.errorTime = System.currentTimeMillis();
    }

    @Override
    public CacheEventType getEventType() {
        return CacheEventType.CACHE_ERROR;
    }
}