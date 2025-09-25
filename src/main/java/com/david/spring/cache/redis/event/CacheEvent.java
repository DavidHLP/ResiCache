package com.david.spring.cache.redis.event;

import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 缓存事件基类
 */
@Data
@SuperBuilder
public abstract class CacheEvent {
    private String cacheName;
    private Object cacheKey;
    private LocalDateTime timestamp;
    private String source;

    public CacheEvent(String cacheName, Object cacheKey, String source) {
        this.cacheName = cacheName;
        this.cacheKey = cacheKey;
        this.source = source;
        this.timestamp = LocalDateTime.now();
    }

    /**
     * 获取事件类型
     */
    public abstract CacheEventType getEventType();
}