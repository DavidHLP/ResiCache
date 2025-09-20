package com.david.spring.cache.redis.cache;

import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.strategy.CacheFetchStrategy;
import com.david.spring.cache.redis.strategy.CacheFetchStrategyManager;
import com.david.spring.cache.redis.strategy.CacheOperationService;
import com.david.spring.cache.redis.support.CacheExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.RedisTemplate;

@Slf4j
public record CacheExecutionHandler(String cacheName, CacheFetchStrategyManager strategyManager,
                                    CacheOperationService cacheOperationService, CacheContextValidator validator) {

	public CacheFetchStrategy.CacheFetchCallback createFetchCallback(Cache cache) {
		return new CacheFetchStrategy.CacheFetchCallback() {
			@Override
			public Cache.ValueWrapper getBaseValue(Object key) {
				return cache.get(key);
			}

			@Override
			public void refresh(CachedInvocation invocation, Object key, String cacheKey, long ttl) {
				try {
					CacheOperationService.CacheRefreshCallback refreshCallback = new CacheOperationService.CacheRefreshCallback() {
						@Override
						public void putCache(Object key, Object value) {
							cache.put(key, value);
						}

						@Override
						public String getCacheName() {
							return cacheName;
						}
					};
					cacheOperationService.doRefresh(invocation, key, cacheKey, ttl, refreshCallback);
				} catch (Exception e) {
					log.error("Cache refresh failed for cache: {}, key: {}, error: {}",
							cacheName, key, e.getMessage(), e);
				}
			}

			@Override
			public long resolveConfiguredTtlSeconds(Object value, Object key) {
				try {
					return cacheOperationService.resolveConfiguredTtlSeconds(value, key, null);
				} catch (Exception e) {
					log.warn("Failed to resolve TTL for cache: {}, key: {}, using default", cacheName, key);
					return -1L;
				}
			}

			@Override
			public boolean shouldPreRefresh(long ttl, long configuredTtl) {
				try {
					return cacheOperationService.shouldPreRefresh(ttl, configuredTtl);
				} catch (Exception e) {
					log.debug("Pre-refresh check failed for cache: {}, defaulting to false", cacheName);
					return false;
				}
			}
		};
	}

	public CacheFetchStrategy.CacheFetchContext createFetchContext(Object key,
	                                                               String cacheKey,
	                                                               Cache.ValueWrapper valueWrapper,
	                                                               CachedInvocation invocation,
	                                                               RedisTemplate<String, Object> redisTemplate,
	                                                               CacheFetchStrategy.CacheFetchCallback callback) {
		return new CacheFetchStrategy.CacheFetchContext(
				cacheName,
				key,
				cacheKey,
				valueWrapper,
				invocation,
				invocation.getCachedInvocationContext(),
				redisTemplate,
				callback
		);
	}

	public Cache.ValueWrapper executeStrategiesWithFallback(CacheFetchStrategy.CacheFetchContext context,
	                                                        Cache.ValueWrapper fallbackValue,
	                                                        Object key) {
		long startTime = System.currentTimeMillis();
		String operationId = generateOperationId(key);

		try {
			log.debug("[{}] Starting strategy execution for cache: {}, key: {}", operationId, cacheName, key);

			if (strategyManager == null) {
				log.error("[{}] Strategy manager is null, using fallback", operationId);
				return fallbackValue;
			}

			Cache.ValueWrapper result = strategyManager.fetch(context);
			long duration = System.currentTimeMillis() - startTime;

			if (result != null) {
				log.debug("[{}] Strategy execution successful in {}ms for cache: {}, key: {}",
						operationId, duration, cacheName, key);
				return result;
			} else {
				log.debug("[{}] Strategy execution returned null in {}ms for cache: {}, key: {}, using fallback",
						operationId, duration, cacheName, key);
				return handleNullResult(context, fallbackValue, operationId);
			}

		} catch (IllegalStateException e) {
			long duration = System.currentTimeMillis() - startTime;
			log.warn("[{}] Strategy configuration error in {}ms for cache: {}, key: {}: {}, using fallback",
					operationId, duration, cacheName, key, e.getMessage());
			return fallbackValue;

		} catch (SecurityException e) {
			long duration = System.currentTimeMillis() - startTime;
			log.error("[{}] Security error in {}ms for cache: {}, key: {}: {}, using fallback",
					operationId, duration, cacheName, key, e.getMessage());
			return fallbackValue;

		} catch (Exception e) {
			long duration = System.currentTimeMillis() - startTime;
			log.error("[{}] Strategy execution failed in {}ms for cache: {}, key: {}: {}, using fallback",
					operationId, duration, cacheName, key, e.getMessage(), e);

			if (isCriticalError(e)) {
				handleCriticalError(context, e, operationId);
			}

			return fallbackValue;
		}
	}

	private Cache.ValueWrapper handleNullResult(CacheFetchStrategy.CacheFetchContext context,
	                                            Cache.ValueWrapper fallbackValue,
	                                            String operationId) {
		CachedInvocationContext invocationContext = context.invocationContext();

		if (invocationContext.cacheNullValues()) {
			log.debug("[{}] Null result accepted due to cacheNullValues=true", operationId);
			return null;
		}

		return fallbackValue;
	}

	private boolean isCriticalError(Exception e) {
		return CacheExceptionHandler.isCriticalException(e);
	}

	private void handleCriticalError(CacheFetchStrategy.CacheFetchContext context,
	                                 Exception e,
	                                 String operationId) {
		log.error("[{}] Critical error detected in cache strategy execution: {}", operationId, e.getMessage());

		try {
			if (context.redisTemplate() != null) {
				// 可以添加连接池状态检查等
			}
		} catch (Exception cleanupError) {
			log.error("[{}] Error during critical error cleanup: {}", operationId, cleanupError.getMessage());
		}
	}

	private String generateOperationId(Object key) {
		return String.format("%s-%d", String.valueOf(key).hashCode(), System.currentTimeMillis() % 10000);
	}
}