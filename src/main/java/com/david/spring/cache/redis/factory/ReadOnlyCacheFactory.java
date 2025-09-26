package com.david.spring.cache.redis.factory;

import com.david.spring.cache.redis.core.RedisCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.stereotype.Component;

/**
 * 只读缓存工厂实现
 * 创建只支持读取操作的缓存，写入操作将被忽略
 */
@Slf4j
@Component
public class ReadOnlyCacheFactory implements CacheFactory {

    @Override
    public Cache createCache(CacheCreationConfig config) {
        log.info("Creating read-only cache '{}' with TTL: {}", config.getCacheName(), config.getDefaultTtl());

        RedisCache redisCache = new RedisCache(
                config.getCacheName(),
                config.getRedisTemplate(),
                config.getDefaultTtl(),
                config.isAllowNullValues()
        );

        return new ReadOnlyCacheWrapper(redisCache);
    }

    @Override
    public boolean supports(CacheType cacheType) {
        return CacheType.READ_ONLY == cacheType;
    }

    @Override
    public int getOrder() {
        return 3; // 只读缓存的优先级
    }

    /**
     * 只读缓存包装器
     * 将所有写入操作转换为无操作
     */
    private static class ReadOnlyCacheWrapper implements Cache {
        private final Cache delegate;

        public ReadOnlyCacheWrapper(Cache delegate) {
            this.delegate = delegate;
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
        public <T> T get(Object key, java.util.concurrent.Callable<T> valueLoader) {
            return delegate.get(key, valueLoader);
        }

        @Override
        public void put(Object key, Object value) {
            // 只读缓存不支持写入操作，忽略
            log.debug("Put operation ignored in read-only cache '{}'", getName());
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            // 只读缓存不支持写入操作，返回现有值
            log.debug("PutIfAbsent operation ignored in read-only cache '{}'", getName());
            return get(key);
        }

        @Override
        public void evict(Object key) {
            // 只读缓存不支持清除操作，忽略
            log.debug("Evict operation ignored in read-only cache '{}'", getName());
        }

        @Override
        public void clear() {
            // 只读缓存不支持清空操作，忽略
            log.debug("Clear operation ignored in read-only cache '{}'", getName());
        }
    }
}