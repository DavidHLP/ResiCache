package com.david.spring.cache.redis.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;

/**
 * 分层缓存实现
 * 结合本地缓存和Redis缓存，提供两级缓存机制
 */
@Slf4j
public class LayeredCache implements Cache {

    private final String name;
    private final Cache localCache;
    private final Cache remoteCache;

    public LayeredCache(String name, Cache localCache, Cache remoteCache) {
        this.name = name;
        this.localCache = localCache;
        this.remoteCache = remoteCache;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return this;
    }

    @Override
    @Nullable
    public ValueWrapper get(Object key) {
        // 先查本地缓存
        ValueWrapper localValue = localCache.get(key);
        if (localValue != null) {
            log.debug("Local cache hit for key: {}", key);
            return localValue;
        }

        // 查远程缓存
        ValueWrapper remoteValue = remoteCache.get(key);
        if (remoteValue != null) {
            log.debug("Remote cache hit for key: {}, updating local cache", key);
            // 将远程缓存的值同步到本地缓存
            localCache.put(key, remoteValue.get());
            return remoteValue;
        }

        log.debug("Cache miss for key: {}", key);
        return null;
    }

    @Override
    @Nullable
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        return wrapper != null ? (T) wrapper.get() : null;
    }

    @Override
    @Nullable
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper result = get(key);
        if (result != null) {
            return (T) result.get();
        }

        try {
            T value = valueLoader.call();
            put(key, value);
            return value;
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        // 同时更新本地和远程缓存
        localCache.put(key, value);
        remoteCache.put(key, value);
        log.debug("Put value to both local and remote cache for key: {}", key);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
        // 先检查本地缓存
        ValueWrapper existing = localCache.get(key);
        if (existing != null) {
            return existing;
        }

        // 检查远程缓存并尝试设置
        ValueWrapper remoteResult = remoteCache.putIfAbsent(key, value);
        if (remoteResult == null) {
            // 远程缓存中不存在，更新本地缓存
            localCache.put(key, value);
            log.debug("PutIfAbsent succeeded for key: {}", key);
            return null;
        } else {
            // 远程缓存中已存在，更新本地缓存
            localCache.put(key, remoteResult.get());
            log.debug("PutIfAbsent failed for key: {}, value already exists", key);
            return remoteResult;
        }
    }

    @Override
    public void evict(Object key) {
        // 从两级缓存中删除
        localCache.evict(key);
        remoteCache.evict(key);
        log.debug("Evicted key from both local and remote cache: {}", key);
    }

    @Override
    public void clear() {
        // 清空两级缓存
        localCache.clear();
        remoteCache.clear();
        log.debug("Cleared both local and remote cache");
    }

    /**
     * 获取本地缓存
     */
    public Cache getLocalCache() {
        return localCache;
    }

    /**
     * 获取远程缓存
     */
    public Cache getRemoteCache() {
        return remoteCache;
    }
}