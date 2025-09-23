package com.david.spring.cache.redis.cache.support;

import com.david.spring.cache.redis.chain.CacheHandlerContext;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;

@Slf4j
public class CacheFetchCallbackFactory {

    public static CacheHandlerContext.CacheFetchCallback create(String cacheName,
                                                                Cache cache,
                                                                CacheOperationService operationService) {
        return new CacheFetchCallbackImpl(cacheName, cache, operationService);
    }

    private static class CacheFetchCallbackImpl implements CacheHandlerContext.CacheFetchCallback {
        private final String cacheName;
        private final Cache cache;
        private final CacheOperationService operationService;

        public CacheFetchCallbackImpl(String cacheName, Cache cache, CacheOperationService operationService) {
            this.cacheName = cacheName;
            this.cache = cache;
            this.operationService = operationService;
        }

        @Override
        public Cache.ValueWrapper getBaseValue(@Nonnull Object key) {
            return cache.get(key);
        }

        @Override
        public void refresh(@Nonnull CachedInvocation invocation, @Nonnull Object key,
                           @Nonnull String cacheKey, long ttl) {
            try {
                CacheOperationService.CacheRefreshCallback refreshCallback = createRefreshCallback();
                operationService.doRefresh(invocation, key, cacheKey, ttl, refreshCallback);
            } catch (Exception e) {
                log.error("Cache refresh failed for cache: {}, key: {}, error: {}",
                        cacheName, key, e.getMessage(), e);
            }
        }

        @Override
        public long resolveConfiguredTtlSeconds(Object value, @Nonnull Object key) {
            try {
                return operationService.resolveConfiguredTtlSeconds(value, key, null);
            } catch (Exception e) {
                log.warn("Failed to resolve TTL for cache: {}, key: {}, using default", cacheName, key);
                return -1L;
            }
        }

        @Override
        public boolean shouldPreRefresh(long ttl, long configuredTtl) {
            try {
                return operationService.shouldPreRefresh(ttl, configuredTtl);
            } catch (Exception e) {
                log.debug("Pre-refresh check failed for cache: {}, defaulting to false", cacheName);
                return false;
            }
        }

        @Override
        public void evictCache(@Nonnull String cacheName, @Nonnull Object key) {
            try {
                cache.evict(key);
                log.debug("Cache key evicted: cache={}, key={}", cacheName, key);
            } catch (Exception e) {
                log.error("Failed to evict cache key: cache={}, key={}, error={}",
                        cacheName, key, e.getMessage(), e);
                throw e;
            }
        }

        @Override
        public void clearCache(@Nonnull String cacheName) {
            try {
                cache.clear();
                log.debug("Cache cleared: cache={}", cacheName);
            } catch (Exception e) {
                log.error("Failed to clear cache: cache={}, error={}", cacheName, e.getMessage(), e);
                throw e;
            }
        }

        @Override
        public void cleanupRegistries(@Nonnull String cacheName, @Nonnull Object key) {
            log.debug("Registry cleanup requested for: cache={}, key={}", cacheName, key);
        }

        @Override
        public void cleanupAllRegistries(@Nonnull String cacheName) {
            log.debug("All registries cleanup requested for: cache={}", cacheName);
        }

        private CacheOperationService.CacheRefreshCallback createRefreshCallback() {
            return new CacheOperationService.CacheRefreshCallback() {
                @Override
                public void putCache(Object key, Object value) {
                    cache.put(key, value);
                }

                @Override
                public String getCacheName() {
                    return cacheName;
                }
            };
        }
    }
}