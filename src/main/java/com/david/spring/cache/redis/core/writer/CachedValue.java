package com.david.spring.cache.redis.core.writer;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CachedValue {

    private Object value;

    private Class<?> type;
    @Builder.Default private long ttl = 60;

    @Builder.Default private long createdTime = System.currentTimeMillis();

    @Builder.Default private long lastAccessTime = System.currentTimeMillis();

    @Builder.Default private long visitTimes = 0L;

    @Builder.Default private boolean expired = false;

    @Builder.Default private long version = 1L;

    public static CachedValue of(Object value, long ttl) {
        return CachedValue.builder()
                .value(value)
                .type(value != null ? value.getClass() : Object.class)
                .ttl(ttl)
                .version(System.nanoTime())
                .build();
    }

    @JsonIgnore
    public boolean isExpired() {
        if (expired) {
            return true;
        }
        if (ttl <= 0) {
            return false;
        }
        long elapsedTime = System.currentTimeMillis() - createdTime;
        return elapsedTime >= ttl * 1000;
    }

    @JsonIgnore
    public void updateAccess() {
        this.lastAccessTime = System.currentTimeMillis();
        this.visitTimes++;
    }

    @JsonIgnore
    public long getRemainingTtl() {
        if (ttl <= 0) {
            return -1;
        }
        long elapsedTime = System.currentTimeMillis() - createdTime;
        long remainingMs = (ttl * 1000) - elapsedTime;
        return Math.max(0, remainingMs / 1000);
    }

    @JsonIgnore
    public long getAge() {
        return (System.currentTimeMillis() - createdTime) / 1000;
    }

    @JsonIgnore
    public void markExpired() {
        this.expired = true;
    }
}
