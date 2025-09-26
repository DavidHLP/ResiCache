package com.david.spring.cache.redis.event.entity;

import com.david.spring.cache.redis.event.CacheEvent;
import com.david.spring.cache.redis.event.CacheEventType;
import lombok.Getter;

/**
 * 缓存操作开始事件
 */
@Getter
public class CacheOperationStartEvent extends CacheEvent {
    private final String operation;
    private final long startTime;
    private final String methodName;

    public CacheOperationStartEvent(String cacheName, Object cacheKey, String source,
                                  String operation, String methodName) {
        super(cacheName, cacheKey, source);
        this.operation = operation;
        this.methodName = methodName;
        this.startTime = System.currentTimeMillis();
    }

    @Override
    public CacheEventType getEventType() {
        return CacheEventType.CACHE_OPERATION_START;
    }
}