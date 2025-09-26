package com.david.spring.cache.redis.factory;

import com.david.spring.cache.redis.core.RedisCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

/**
 * 写透缓存工厂实现
 * 创建支持写透模式的缓存，数据同时写入缓存和底层存储
 */
@Slf4j
@Component
public class WriteThroughCacheFactory implements CacheFactory {

    @Override
    public Cache createCache(CacheCreationConfig config) {
        log.info("Creating write-through cache '{}' with TTL: {}", config.getCacheName(), config.getDefaultTtl());

        RedisCache redisCache = new RedisCache(
                config.getCacheName(),
                config.getRedisTemplate(),
                config.getDefaultTtl(),
                config.isAllowNullValues()
        );

        return new WriteThroughCacheWrapper(redisCache, config);
    }

    @Override
    public boolean supports(CacheType cacheType) {
        return CacheType.WRITE_THROUGH == cacheType;
    }

    @Override
    public int getOrder() {
        return 4; // 写透缓存的优先级
    }

    /**
     * 写透缓存包装器
     * 实现写透模式，确保数据一致性
     */
    private static class WriteThroughCacheWrapper implements Cache {
        private final Cache delegate;
        private final CacheCreationConfig config;

        public WriteThroughCacheWrapper(Cache delegate, CacheCreationConfig config) {
            this.delegate = delegate;
            this.config = config;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public Object getNativeCache() {
            return delegate.getNativeCache();
        }

        @Override
        public ValueWrapper get(Object key) {
            return delegate.get(key);
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            return delegate.get(key, type);
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            // 写透模式下，如果缓存未命中，加载数据并同时写入缓存
            ValueWrapper cached = delegate.get(key);
            if (cached != null) {
                @SuppressWarnings("unchecked")
                T value = (T) cached.get();
                return value;
            }

            try {
                T value = valueLoader.call();
                if (value != null || config.isAllowNullValues()) {
                    // 同步写入缓存（写透模式的核心）
                    delegate.put(key, value);
                    log.debug("Write-through: loaded and cached value for key '{}' in cache '{}'", key, getName());
                }
                return value;
            } catch (Exception e) {
                log.error("Error loading value for key '{}' in write-through cache '{}'", key, getName(), e);
                throw new RuntimeException("Failed to load value through write-through cache", e);
            }
        }

        @Override
        public void put(Object key, Object value) {
            // 写透模式：同步写入缓存
            delegate.put(key, value);
            log.debug("Write-through: value written to cache '{}' for key '{}'", getName(), key);

            // 在实际应用中，这里还会同步写入底层数据源
            // 例如：dataSource.save(key, value);
            // 为了演示，我们只是记录日志
            log.debug("Write-through: value would be persisted to underlying store for key '{}'", key);
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            ValueWrapper existing = delegate.get(key);
            if (existing == null) {
                put(key, value);
                return null;
            }
            return existing;
        }

        @Override
        public void evict(Object key) {
            delegate.evict(key);
            log.debug("Write-through: evicted key '{}' from cache '{}'", key, getName());

            // 在写透模式中，通常也需要从底层存储中删除
            log.debug("Write-through: key '{}' would be deleted from underlying store", key);
        }

        @Override
        public void clear() {
            delegate.clear();
            log.debug("Write-through: cleared cache '{}'", getName());

            // 在写透模式中，通常也需要清空底层存储
            log.debug("Write-through: underlying store would be cleared");
        }
    }
}