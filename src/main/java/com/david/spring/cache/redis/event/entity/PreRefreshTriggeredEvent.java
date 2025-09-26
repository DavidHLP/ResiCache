package com.david.spring.cache.redis.event.entity;

import com.david.spring.cache.redis.event.CacheEvent;
import com.david.spring.cache.redis.event.CacheEventType;
import lombok.Getter;
import java.time.Duration;

/**
 * 预刷新触发事件
 */
@Getter
public class PreRefreshTriggeredEvent extends CacheEvent {
    private final Duration timeToExpire;
    private final String reason;
    private final Object currentValue;

    public PreRefreshTriggeredEvent(String cacheName, Object cacheKey, String source,
                                  Duration timeToExpire, String reason, Object currentValue) {
        super(cacheName, cacheKey, source);
        this.timeToExpire = timeToExpire;
        this.reason = reason;
        this.currentValue = currentValue;
    }

    @Override
    public CacheEventType getEventType() {
        return CacheEventType.PRE_REFRESH_TRIGGERED;
    }
}