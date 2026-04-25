package io.github.davidhlp.spring.cache.redis.event;

import org.springframework.context.ApplicationEvent;

import java.lang.ref.WeakReference;
import java.time.Instant;

/**
 * Cache eviction event published when a cache entry is evicted.
 * Uses weak references to avoid memory leaks.
 */
public class CacheEvictedEvent extends ApplicationEvent {

    private final String cacheName;
    private final String key;
    private final WeakReference<Object> valueRef;
    private final EvictionReason reason;
    private final Instant evictedAt;

    public enum EvictionReason {
        SIZE_LIMIT,
        TIME_BASED,
        MANUAL
    }

    public CacheEvictedEvent(Object source, String cacheName, String key, Object value, EvictionReason reason) {
        super(source);
        this.cacheName = cacheName;
        this.key = key;
        this.valueRef = new WeakReference<>(value);
        this.reason = reason;
        this.evictedAt = Instant.now();
    }

    public String getCacheName() {
        return cacheName;
    }

    public String getKey() {
        return key;
    }

    public Object getValue() {
        return valueRef.get();
    }

    public EvictionReason getReason() {
        return reason;
    }

    public Instant getEvictedAt() {
        return evictedAt;
    }
}
