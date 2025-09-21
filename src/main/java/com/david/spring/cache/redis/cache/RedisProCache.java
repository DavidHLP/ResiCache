package com.david.spring.cache.redis.cache;

import com.david.spring.cache.redis.config.CacheGuardProperties;
import com.david.spring.cache.redis.lock.DistributedLock;
import com.david.spring.cache.redis.lock.LockUtils;
import com.david.spring.cache.redis.protection.CacheBreakdown;
import com.david.spring.cache.redis.protection.CachePenetration;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.factory.RegistryFactory;
import com.david.spring.cache.redis.strategy.CacheFetchStrategy;
import com.david.spring.cache.redis.strategy.CacheFetchStrategyManager;
import com.david.spring.cache.redis.strategy.CacheOperationService;
import com.david.spring.cache.redis.strategy.SimpleFetchStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class RedisProCache extends RedisCache {

	private final CacheGuardProperties properties;
	private final RedisTemplate<String, Object> redisTemplate;
	private final RegistryFactory registryFactory;
	private final Executor executor;
	private final RedisCacheConfiguration cacheConfiguration;
	private final DistributedLock distributedLock;
	private final CachePenetration cachePenetration;
	private final CacheBreakdown cacheBreakdown;
	private final CacheFetchStrategyManager strategyManager;
	private final CacheOperationService cacheOperationService;

	// 新增的工具类
	private final CacheContextValidator validator;
	private final CacheExecutionHandler executionHandler;

	public RedisProCache(
			String name,
			RedisCacheWriter cacheWriter,
			RedisCacheConfiguration cacheConfiguration,
			RedisTemplate<String, Object> redisTemplate,
			RegistryFactory registryFactory,
			Executor executor,
			DistributedLock distributedLock,
			CachePenetration cachePenetration,
			CacheBreakdown cacheBreakdown,
			CacheFetchStrategyManager strategyManager,
			CacheOperationService cacheOperationService,
			CacheGuardProperties properties) {
		super(name, cacheWriter, cacheConfiguration);
		this.redisTemplate = Objects.requireNonNull(redisTemplate);
		this.registryFactory = Objects.requireNonNull(registryFactory);
		this.executor = Objects.requireNonNull(executor);
		this.cacheConfiguration = Objects.requireNonNull(cacheConfiguration);
		this.distributedLock = Objects.requireNonNull(distributedLock);
		this.cachePenetration = Objects.requireNonNull(cachePenetration);
		this.cacheBreakdown = Objects.requireNonNull(cacheBreakdown);
		this.strategyManager = Objects.requireNonNull(strategyManager);
		this.cacheOperationService = Objects.requireNonNull(cacheOperationService);
		this.properties = Objects.requireNonNull(properties);

		// 初始化工具类
		this.validator = new CacheContextValidator(strategyManager);
		this.executionHandler = new CacheExecutionHandler(name, strategyManager,
				cacheOperationService, validator);

		// 验证策略集成
		validateStrategyIntegration();
	}


	private void validateStrategyIntegration() {
		try {
			List<CacheFetchStrategy> strategies = strategyManager.getAllStrategies();
			if (strategies.isEmpty()) {
				log.warn("No strategies registered in cache: {}", getName());
				return;
			}

			log.info("Strategy integration validation for cache: {} - {} strategies registered",
					getName(), strategies.size());

			// 验证是否有默认策略
			boolean hasDefaultStrategy = strategies.stream()
					.anyMatch(s -> s instanceof SimpleFetchStrategy);

			if (!hasDefaultStrategy) {
				log.warn("No default SimpleFetchStrategy found for cache: {}", getName());
			}

			// 输出策略信息
			if (log.isDebugEnabled()) {
				log.debug("Strategy configuration for cache {}:\n{}", getName(), strategyManager.getStrategyInfo());
			}

		} catch (Exception e) {
			log.error("Strategy integration validation failed for cache: {}, error: {}", getName(), e.getMessage(), e);
		}
	}

	@Override
	@Nullable
	public ValueWrapper get(@NonNull Object key) {
		ValueWrapper baseValue = super.get(key);

		CachedInvocation invocation = registryFactory.getCacheInvocationRegistry().get(getName(), key).orElse(null);
		if (invocation == null || invocation.getCachedInvocationContext() == null) {
			log.debug("No invocation context found for cache: {}, key: {}, returning value as-is",
					getName(), key);
			return baseValue;
		}

		CachedInvocationContext invocationContext = invocation.getCachedInvocationContext();
		if (!validator.isValidInvocationContext(invocationContext)) {
			log.warn("Invalid invocation context for cache: {}, key: {}, returning base value", getName(), key);
			return baseValue;
		}

		if (!validator.shouldExecuteStrategies(invocationContext, baseValue)) {
			log.debug("Skipping strategy execution for cache: {}, key: {}", getName(), key);
			return baseValue;
		}

		String cacheKey = createCacheKey(key);
		CacheFetchStrategy.CacheFetchCallback callback = executionHandler.createFetchCallback(this);
		CacheFetchStrategy.CacheFetchContext context = executionHandler.createFetchContext(
				key, cacheKey, baseValue, invocation, redisTemplate, callback);

		if (!validator.isValidFetchContext(context)) {
			log.warn("Invalid fetch context for cache: {}, key: {}, returning base value", getName(), key);
			return baseValue;
		}

		return executionHandler.executeStrategiesWithFallback(context, baseValue, key);
	}

	// TTL、刷新、包装等方法已下沉到 CacheOperationService

	@Override
	public void put(@NonNull Object key, @Nullable Object value) {
		// 获取用户配置的上下文信息，避免重复的TTL随机化
		CachedInvocationContext invocationContext = getInvocationContext(key);
		Object toStore = cacheOperationService.wrapIfMataAbsent(value, key, cacheConfiguration, invocationContext);
		super.put(key, toStore);
		// 成功写入后将 key 加入布隆过滤器（值非空时）
		if (value != null) {
			String membershipKey = String.valueOf(key);
			try {
				cachePenetration.addIfEnabled(getName(), membershipKey);
			} catch (Exception e) {
				log.error("Failed to add key to bloom filter: cache={}, key={}, error={}",
						getName(), key, e.getMessage(), e);
			}
		}
		// 雪崩保护：统一调用
		String cacheKey = createCacheKey(key);
		cacheOperationService.applyLitteredExpire(key, toStore, cacheKey, redisTemplate);
	}

	@Override
	@NonNull
	public ValueWrapper putIfAbsent(@NonNull Object key, @Nullable Object value) {
		CachedInvocationContext invocationContext = getInvocationContext(key);
		Object toStore = cacheOperationService.wrapIfMataAbsent(value, key, cacheConfiguration, invocationContext);
		String cacheKey = createCacheKey(key);
		boolean firstInsert = cacheOperationService.wasKeyAbsentBeforePut(cacheKey, redisTemplate);
		ValueWrapper previous = super.putIfAbsent(key, toStore);
		if (firstInsert) {
			try {
				if (value != null) {
					String membershipKey = String.valueOf(key);
					cachePenetration.addIfEnabled(getName(), membershipKey);
				}
			} catch (Exception e) {
				log.debug("Failed to add key to bloom filter: cache={}, key={}", getName(), key, e);
			}
			cacheOperationService.applyLitteredExpire(key, toStore, cacheKey, redisTemplate);
		}
		return previous;
	}

	@Override
	@Nullable
	public <T> T get(@NonNull Object key, @Nullable Class<T> type) {
		ValueWrapper wrapper = this.get(key);
		if (wrapper == null)
			return null;
		Object val = wrapper.get();
		if (type == null || type == Object.class) {
			@SuppressWarnings("unchecked")
			T casted = (T) val;
			return casted;
		}
		return (type.isInstance(val) ? type.cast(val) : null);
	}

	@Override
	@NonNull
	public <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
		String cacheKey = createCacheKey(key);
		String membershipKey = String.valueOf(key);
		ReentrantLock localLock = registryFactory.getCacheInvocationRegistry().obtainLock(getName(), key);
		String distKey = "cache:load:" + cacheKey;
		long leaseTimeSec = 30L; // 防御性租期，避免锁永久占用
		log.debug(
				"First-load attempt: name={}, redisKey={}, distKey={}, leaseTimeSec={}",
				getName(),
				cacheKey,
				distKey,
				leaseTimeSec);

		// Bloom 预检：若启用且断言“不可能存在”，直接抛出 ValueRetrievalException，阻断穿透
		if (cachePenetration.isEnabled(getName())) {
			boolean mightContain = cachePenetration.mightContain(getName(), membershipKey);
			if (!mightContain) {
				log.debug("Bloom blocked load: name={}, bloomKey={}", getName(), membershipKey);
				throw new Cache.ValueRetrievalException(
						key,
						valueLoader,
						new NoSuchElementException("Blocked by Bloom filter: " + membershipKey));
			}
		}
		try {
			T result = cacheBreakdown.loadWithProtection(
					getName(),
					distKey,
					localLock,
					leaseTimeSec,
					TimeUnit.SECONDS,
					() -> {
						ValueWrapper w = super.get(key);
						if (w != null) {
							Object v = w.get();
							if (v != null) {
								// 命中缓存则补偿加入 Bloom
								try {
									cachePenetration.addIfEnabled(getName(), membershipKey);
								} catch (Exception e) {
									log.debug("Failed to add key to bloom filter during cache hit: cache={}, key={}", getName(), key, e);
								}
								@SuppressWarnings("unchecked")
								T casted = (T) v;
								return casted;
							}
						}
						return null;
					},
					valueLoader::call,
					(val) -> this.put(key, val));
			log.debug(
					"First-load finished: name={}, redisKey={}, distKey={}",
					getName(),
					cacheKey,
					distKey);
			return result;
		} catch (Exception ex) {
			throw new Cache.ValueRetrievalException(key, valueLoader, ex);
		}
	}

	@Override
	public void evict(@NonNull Object key) {
		String cacheKey = createCacheKey(key);
		ReentrantLock localLock = registryFactory.getEvictInvocationRegistry().obtainLock(getName(), key);
		String distKey = "cache:evict:" + cacheKey;
		long leaseTimeSec = 10L;
		boolean executed = LockUtils.runWithLocalTryThenDistTry(
				localLock,
				distributedLock,
				distKey,
				0L,
				leaseTimeSec,
				TimeUnit.SECONDS,
				() -> {
					doEvictInternal(key);
					scheduleSecondDeleteForKey(key);
				});
		if (!executed) {
			// 降级：未能加锁也尝试执行，以提升可用性
			doEvictInternal(key);
			scheduleSecondDeleteForKey(key);
		}
	}

	@Override
	public void clear() {
		String distKey = "cache:evictAll:" + getName();
		ReentrantLock localLock = registryFactory.getEvictInvocationRegistry().obtainLock(getName(), "*");
		long leaseTimeSec = 15L;
		boolean executed = LockUtils.runWithLocalTryThenDistTry(
				localLock,
				distributedLock,
				distKey,
				0L,
				leaseTimeSec,
				TimeUnit.SECONDS,
				() -> {
					doClearInternal();
					scheduleSecondClear();
				});
		if (!executed) {
			doClearInternal();
			scheduleSecondClear();
		}
	}

	private byte[] createAndConvertCacheKey(Object key) {
		return serializeCacheKey(createCacheKey(key));
	}


	/**
	 * 获取调用上下文信息（用于TTL处理优化）
	 */
	private CachedInvocationContext getInvocationContext(Object key) {
		try {
			CachedInvocation invocation = registryFactory.getCacheInvocationRegistry().get(getName(), key).orElse(null);
			return invocation != null ? invocation.getCachedInvocationContext() : null;
		} catch (Exception e) {
			log.error("Failed to get invocation context: cache={}, key={}, error={}",
					getName(), key, e.getMessage(), e);
			return null;
		}
	}


	@Override
	protected Object fromStoreValue(@Nullable Object storeValue) {
		Object v = super.fromStoreValue(storeValue);
		return cacheOperationService.fromStoreValue(v);
	}

	// 过期时间设置和存在性检查方法已下沉到 CacheOperationService

	/** 立即删除单键并清理本地注册。 */
	private void doEvictInternal(@NonNull Object key) {
		try {
			getCacheWriter().remove(getName(), createAndConvertCacheKey(key));
		} finally {
			try {
				registryFactory.getCacheInvocationRegistry().remove(getName(), key);
			} catch (Exception e) {
				log.debug("Failed to remove key from cache registry: cache={}, key={}", getName(), key, e);
			}
			try {
				registryFactory.getEvictInvocationRegistry().remove(getName(), key);
			} catch (Exception e) {
				log.debug("Failed to remove key from evict registry: cache={}, key={}", getName(), key, e);
			}
		}
	}

	/** 延迟二次删除（单键），并在二次删除时再次尝试加锁以避免竞态。 */
	private void scheduleSecondDeleteForKey(@NonNull Object key) {
		Executor delayed = CompletableFuture.delayedExecutor(
				properties.getDoubleDeleteDelayMs(), TimeUnit.MILLISECONDS, executor);
		CompletableFuture.runAsync(
				() -> {
					String cacheKey = createCacheKey(key);
					String distKey = "cache:evict:" + cacheKey;
					ReentrantLock localLock = registryFactory.getEvictInvocationRegistry().obtainLock(getName(), key);
					LockUtils.runWithLocalTryThenDistTry(
							localLock,
							distributedLock,
							distKey,
							0L,
							5L,
							TimeUnit.SECONDS,
							() -> doEvictInternal(key));
				},
				delayed);
	}

	/** 立即全量清理并清理本地注册。 */
	private void doClearInternal() {
		try {
			super.clear();
		} finally {
			try {
				registryFactory.getCacheInvocationRegistry().removeAll(getName());
			} catch (Exception e) {
				log.debug("Failed to clear cache registry: cache={}", getName(), e);
			}
			try {
				registryFactory.getEvictInvocationRegistry().removeAll(getName());
			} catch (Exception e) {
				log.debug("Failed to clear evict registry: cache={}", getName(), e);
			}
		}
	}

	/** 延迟二次全量删除，并在二次删除时再次尝试加锁以避免竞态。 */
	private void scheduleSecondClear() {
		Executor delayed = CompletableFuture.delayedExecutor(
				properties.getDoubleDeleteDelayMs(), TimeUnit.MILLISECONDS, executor);
		CompletableFuture.runAsync(
				() -> {
					String distKey = "cache:evictAll:" + getName();
					ReentrantLock localLock = registryFactory.getEvictInvocationRegistry().obtainLock(getName(), "*");
					LockUtils.runWithLocalTryThenDistTry(
							localLock,
							distributedLock,
							distKey,
							0L,
							5L,
							TimeUnit.SECONDS,
							this::doClearInternal);
				},
				delayed);
	}

	/**
	 * 缓存上下文验证器内部类
	 */
		private record CacheContextValidator(CacheFetchStrategyManager strategyManager) {

		public boolean isValidInvocationContext(CachedInvocationContext context) {
				if (context == null) {
					return false;
				}

				if (!validateBasicProperties(context)) {
					return false;
				}

				if (!validateNumericRanges(context)) {
					return false;
				}

				if (!validateLogicalConsistency(context)) {
					return false;
				}

				validateLockConfiguration(context);

				return true;
			}

			public boolean isValidFetchContext(CacheFetchStrategy.CacheFetchContext context) {
				return context != null
						&& context.cacheName() != null
						&& context.key() != null
						&& context.cacheKey() != null
						&& context.redisTemplate() != null
						&& context.callback() != null;
			}

			public boolean shouldExecuteStrategies(CachedInvocationContext invocationContext,
			                                       ValueWrapper baseValue) {
				if (invocationContext.fetchStrategy() == CachedInvocationContext.FetchStrategyType.SIMPLE) {
					return baseValue == null || invocationContext.cacheNullValues();
				}

				if (invocationContext.useBloomFilter()) {
					return true;
				}

				if (invocationContext.enablePreRefresh() && baseValue != null) {
					return true;
				}

				if (baseValue == null) {
					return true;
				}

				return invocationContext.distributedLock() || invocationContext.internalLock();
			}

			private boolean validateBasicProperties(CachedInvocationContext context) {
				if (context.cacheNames() == null || context.cacheNames().length == 0) {
					log.debug("Invalid context: missing cache names");
					return false;
				}

				if (context.fetchStrategy() == null) {
					log.debug("Invalid context: missing fetch strategy");
					return false;
				}
				return true;
			}

			private boolean validateNumericRanges(CachedInvocationContext context) {
				if (context.variance() < 0 || context.variance() > 1) {
					log.debug("Invalid context: variance {} out of range [0,1]", context.variance());
					return false;
				}

				if (context.preRefreshThreshold() < 0 || context.preRefreshThreshold() > 1) {
					log.debug("Invalid context: preRefreshThreshold {} out of range [0,1]", context.preRefreshThreshold());
					return false;
				}
				return true;
			}

			private boolean validateLogicalConsistency(CachedInvocationContext context) {
				if (context.enablePreRefresh() && context.ttl() <= 0) {
					log.debug("Invalid context: preRefresh enabled but TTL <= 0");
					return false;
				}

				if (context.randomTtl() && context.variance() <= 0) {
					log.debug("Invalid context: randomTtl enabled but variance <= 0");
					return false;
				}
				return true;
			}

			private void validateLockConfiguration(CachedInvocationContext context) {
				if (context.distributedLock() &&
						(context.distributedLockName() == null || context.distributedLockName().trim().isEmpty())) {
					log.debug("Invalid context: distributedLock enabled but no lock name specified");
				}
			}
		}

	/**
	 * 缓存执行处理器内部类
	 */
		private record CacheExecutionHandler(String cacheName, CacheFetchStrategyManager strategyManager,
		                                     CacheOperationService cacheOperationService, CacheContextValidator validator) {

		public CacheFetchStrategy.CacheFetchCallback createFetchCallback(Cache cache) {
				return new CacheFetchStrategy.CacheFetchCallback() {
					@Override
					public ValueWrapper getBaseValue(Object key) {
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
			                                                               ValueWrapper valueWrapper,
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

			public ValueWrapper executeStrategiesWithFallback(CacheFetchStrategy.CacheFetchContext context,
			                                                  ValueWrapper fallbackValue,
			                                                  Object key) {
				long startTime = System.currentTimeMillis();
				String operationId = generateOperationId(key);

				try {
					log.debug("[{}] Starting strategy execution for cache: {}, key: {}", operationId, cacheName, key);

					if (strategyManager == null) {
						log.error("[{}] Strategy manager is null, using fallback", operationId);
						return fallbackValue;
					}

					ValueWrapper result = strategyManager.fetch(context);
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

			private ValueWrapper handleNullResult(CacheFetchStrategy.CacheFetchContext context,
			                                      ValueWrapper fallbackValue,
			                                      String operationId) {
				CachedInvocationContext invocationContext = context.invocationContext();

				if (invocationContext.cacheNullValues()) {
					log.debug("[{}] Null result accepted due to cacheNullValues=true", operationId);
					return null;
				}

				return fallbackValue;
			}

			private boolean isCriticalError(Exception e) {
				// 检查严重异常类型
				Throwable cause = e.getCause();
				if (cause instanceof OutOfMemoryError || cause instanceof StackOverflowError) {
					return true;
				}

				// 检查特定异常类型
				if (e instanceof java.util.concurrent.TimeoutException
						|| e instanceof java.io.IOException) {
					return true;
				}

				// 检查连接异常
				String message = e.getMessage();
				if (message != null) {
					String lowerMessage = message.toLowerCase();
					return lowerMessage.contains("connection")
							|| lowerMessage.contains("timeout")
							|| lowerMessage.contains("pool exhausted")
							|| lowerMessage.contains("refused");
				}

				return false;
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
}
