package com.david.spring.cache.redis.event.entity;

import com.david.spring.cache.redis.event.CacheEvent;
import com.david.spring.cache.redis.event.CacheEventType;
import lombok.Getter;
import java.time.Duration;

/**
 * 预刷新完成事件
 */
@Getter
public class PreRefreshCompletedEvent extends CacheEvent {
    private final Object newValue;
    private final Object oldValue;
    private final Duration refreshTime;
    private final boolean successful;

    public PreRefreshCompletedEvent(String cacheName, Object cacheKey, String source,
                                  Object newValue, Object oldValue, Duration refreshTime,
                                  boolean successful) {
        super(cacheName, cacheKey, source);
        this.newValue = newValue;
        this.oldValue = oldValue;
        this.refreshTime = refreshTime;
        this.successful = successful;
    }

    @Override
    public CacheEventType getEventType() {
        return CacheEventType.PRE_REFRESH_COMPLETED;
    }
}