package com.david.spring.cache.redis.strategy.support;

import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.strategy.CacheFetchStrategy;
import com.david.spring.cache.redis.strategy.impl.CacheFetchStrategyManager;
import jakarta.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 缓存策略执行器
 * 专门负责策略的创建和执行逻辑
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheStrategyExecutor {

	/**
	 * 创建获取回调
	 */
	public CacheFetchStrategy.CacheFetchCallback createFetchCallback(String cacheName,
	                                                                 Cache cache,
	                                                                 CacheOperationService operationService) {
		return new CacheFetchStrategy.CacheFetchCallback() {
			@Override
			public Cache.ValueWrapper getBaseValue(@Nonnull Object key) {
				return cache.get(key);
			}

			@Override
			public void refresh(@Nonnull CachedInvocation invocation, @Nonnull Object key, @Nonnull String cacheKey, long ttl) {
				try {
					CacheOperationService.CacheRefreshCallback refreshCallback =
							new CacheOperationService.CacheRefreshCallback() {
								@Override
								public void putCache(Object key, Object value) {
									cache.put(key, value);
								}

								@Override
								public String getCacheName() {
									return cacheName;
								}
							};
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
		};
	}

	/**
	 * 创建获取上下文
	 */
	public CacheFetchStrategy.CacheFetchContext createFetchContext(String cacheName,
	                                                               Object key,
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

	/**
	 * 执行策略并处理异常
	 */
	public Cache.ValueWrapper executeStrategiesWithFallback(CacheFetchStrategyManager strategyManager,
	                                                        CacheFetchStrategy.CacheFetchContext context,
	                                                        Cache.ValueWrapper fallbackValue,
	                                                        Object key,
	                                                        String cacheName) {
		long startTime = System.currentTimeMillis();
		String operationId = generateOperationId(key);

		try {
			log.debug("[{}] Starting strategy execution: cache={}, key={}", operationId, cacheName, key);

			if (strategyManager == null) {
				log.error("[{}] Strategy manager is null, using fallback: cache={}, key={}",
						operationId, cacheName, key);
				return fallbackValue;
			}

			Cache.ValueWrapper result = strategyManager.fetch(context);
			long duration = System.currentTimeMillis() - startTime;

			if (result != null) {
				logSuccessfulExecution(operationId, duration, cacheName, key, result);
				return result;
			} else {
				log.debug("[{}] Strategy execution returned null in {}ms: cache={}, key={}, processing null result",
						operationId, duration, cacheName, key);
				return handleNullResult(context, fallbackValue, operationId);
			}

		} catch (Exception e) {
			long duration = System.currentTimeMillis() - startTime;
			log.warn("[{}] Strategy execution failed in {}ms for cache: {}, key: {}: {}, using fallback",
					operationId, duration, cacheName, key, e.getMessage());
			return fallbackValue;
		}
	}

	/**
	 * 处理策略返回null结果
	 */
	private Cache.ValueWrapper handleNullResult(CacheFetchStrategy.CacheFetchContext context,
	                                            Cache.ValueWrapper fallbackValue,
	                                            String operationId) {
		CachedInvocationContext invocationContext = context.invocationContext();

		// 优化：检查是否允许空值缓存
		if (invocationContext.cacheNullValues()) {
			log.debug("[{}] Null result accepted due to cacheNullValues=true: cache={}, key={}",
					operationId, context.cacheName(), context.key());
			return null; // 返回null表示空值缓存
		}

		log.debug("[{}] Using fallback value due to null result and cacheNullValues=false: cache={}, key={}",
				operationId, context.cacheName(), context.key());
		return fallbackValue;
	}

	/**
	 * 记录成功执行的日志
	 */
	private void logSuccessfulExecution(String operationId, long duration,
	                                    String cacheName, Object key,
	                                    Cache.ValueWrapper result) {
		if (duration > 100) {
			log.warn("[{}] Slow strategy execution in {}ms: cache={}, key={}, hasValue={}",
					operationId, duration, cacheName, key, result.get() != null);
		} else {
			log.debug("[{}] Strategy execution successful in {}ms: cache={}, key={}, hasValue={}",
					operationId, duration, cacheName, key, result.get() != null);
		}
	}


	private String generateOperationId(Object key) {
		return String.format("%s-%d", String.valueOf(key).hashCode(), System.currentTimeMillis() % 10000);
	}
}