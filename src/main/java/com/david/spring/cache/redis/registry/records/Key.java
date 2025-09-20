package com.david.spring.cache.redis.registry.records;

import jakarta.annotation.Nonnull;

public record Key(String cacheName, Object key) {

    @Override
    @Nonnull
    public String toString() {
        return cacheName + "::" + key;
    }
}
