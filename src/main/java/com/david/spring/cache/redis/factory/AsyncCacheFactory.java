package com.david.spring.cache.redis.factory;

import com.david.spring.cache.redis.core.RedisCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * 异步缓存工厂实现
 * 创建支持异步操作的缓存，提高性能
 */
@Slf4j
@Component
public class AsyncCacheFactory implements CacheFactory {

    private final Executor asyncExecutor;

    public AsyncCacheFactory() {
        // 使用共享的ForkJoinPool作为默认异步执行器
        this.asyncExecutor = ForkJoinPool.commonPool();
    }

    @Override
    public Cache createCache(CacheCreationConfig config) {
        log.info("Creating async cache '{}' with TTL: {}", config.getCacheName(), config.getDefaultTtl());

        RedisCache redisCache = new RedisCache(
                config.getCacheName(),
                config.getRedisTemplate(),
                config.getDefaultTtl(),
                config.isAllowNullValues()
        );

        // 从配置中获取自定义执行器
        Executor executor = config.getAdditionalProperty("asyncExecutor", this.asyncExecutor);

        return new AsyncCacheWrapper(redisCache, executor, config);
    }

    @Override
    public boolean supports(CacheType cacheType) {
        return CacheType.ASYNC == cacheType;
    }

    @Override
    public int getOrder() {
        return 5; // 异步缓存的优先级
    }

    /**
     * 异步缓存包装器
     * 提供异步操作支持
     */
    private static class AsyncCacheWrapper implements Cache {
        private final Cache delegate;
        private final Executor executor;
        private final CacheCreationConfig config;

        public AsyncCacheWrapper(Cache delegate, Executor executor, CacheCreationConfig config) {
            this.delegate = delegate;
            this.executor = executor;
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
            // 同步读取保持一致性
            return delegate.get(key);
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            // 同步读取保持一致性
            return delegate.get(key, type);
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            ValueWrapper cached = delegate.get(key);
            if (cached != null) {
                @SuppressWarnings("unchecked")
                T value = (T) cached.get();
                return value;
            }

            try {
                T value = valueLoader.call();
                if (value != null || config.isAllowNullValues()) {
                    // 异步写入缓存，不阻塞返回
                    asyncPut(key, value);
                }
                return value;
            } catch (Exception e) {
                log.error("Error loading value for key '{}' in async cache '{}'", key, getName(), e);
                throw new RuntimeException("Failed to load value in async cache", e);
            }
        }

        @Override
        public void put(Object key, Object value) {
            // 异步写入，不阻塞调用线程
            asyncPut(key, value);
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            ValueWrapper existing = delegate.get(key);
            if (existing == null) {
                asyncPut(key, value);
                return null;
            }
            return existing;
        }

        @Override
        public void evict(Object key) {
            // 异步删除
            asyncEvict(key);
        }

        @Override
        public void clear() {
            // 异步清空
            asyncClear();
        }

        /**
         * 异步写入缓存
         */
        private void asyncPut(Object key, Object value) {
            CompletableFuture.runAsync(() -> {
                try {
                    delegate.put(key, value);
                    log.debug("Async put completed for key '{}' in cache '{}'", key, getName());
                } catch (Exception e) {
                    log.error("Async put failed for key '{}' in cache '{}'", key, getName(), e);
                }
            }, executor);
        }

        /**
         * 异步删除缓存项
         */
        private void asyncEvict(Object key) {
            CompletableFuture.runAsync(() -> {
                try {
                    delegate.evict(key);
                    log.debug("Async evict completed for key '{}' in cache '{}'", key, getName());
                } catch (Exception e) {
                    log.error("Async evict failed for key '{}' in cache '{}'", key, getName(), e);
                }
            }, executor);
        }

        /**
         * 异步清空缓存
         */
        private void asyncClear() {
            CompletableFuture.runAsync(() -> {
                try {
                    delegate.clear();
                    log.debug("Async clear completed for cache '{}'", getName());
                } catch (Exception e) {
                    log.error("Async clear failed for cache '{}'", getName(), e);
                }
            }, executor);
        }
    }
}