package com.david.spring.cache.redis.cache;

import com.david.spring.cache.redis.lock.DistributedLock;
import com.david.spring.cache.redis.lock.LockUtils;
import com.david.spring.cache.redis.protection.CacheBreakdown;
import com.david.spring.cache.redis.protection.CachePenetration;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import com.david.spring.cache.redis.registry.EvictInvocationRegistry;
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

	private static final long DOUBLE_DELETE_DELAY_MS = 300L;
	private final RedisTemplate<String, Object> redisTemplate;
	private final CacheInvocationRegistry registry;
	private final EvictInvocationRegistry evictRegistry;
	private final Executor executor;
	private final RedisCacheConfiguration cacheConfiguration;
	private final DistributedLock distributedLock;
	private final CachePenetration cachePenetration;
	private final CacheBreakdown cacheBreakdown;
	private final CacheFetchStrategyManager strategyManager;
	private final CacheOperationService cacheOperationService;

	public RedisProCache(
			String name,
			RedisCacheWriter cacheWriter,
			RedisCacheConfiguration cacheConfiguration,
			RedisTemplate<String, Object> redisTemplate,
			CacheInvocationRegistry registry,
			EvictInvocationRegistry evictRegistry,
			Executor executor,
			DistributedLock distributedLock,
			CachePenetration cachePenetration,
			CacheBreakdown cacheBreakdown,
			CacheFetchStrategyManager strategyManager,
			CacheOperationService cacheOperationService) {
		super(name, cacheWriter, cacheConfiguration);
		this.redisTemplate = Objects.requireNonNull(redisTemplate);
		this.registry = Objects.requireNonNull(registry);
		this.evictRegistry = Objects.requireNonNull(evictRegistry);
		this.executor = Objects.requireNonNull(executor);
		this.cacheConfiguration = Objects.requireNonNull(cacheConfiguration);
		this.distributedLock = Objects.requireNonNull(distributedLock);
		this.cachePenetration = Objects.requireNonNull(cachePenetration);
		this.cacheBreakdown = Objects.requireNonNull(cacheBreakdown);
		this.strategyManager = Objects.requireNonNull(strategyManager);
		this.cacheOperationService = Objects.requireNonNull(cacheOperationService);

		// 验证策略集成
		validateStrategyIntegration();
	}

	private CachedInvocationContext getContext(Object key) {
		try {
			CachedInvocation invocation = registry.get(getName(), key).orElse(null);
			if (invocation == null) {
				log.debug("No invocation found for cache: {}, key: {}, using default context", getName(), key);
				return createDefaultContext();
			}

			CachedInvocationContext context = invocation.getCachedInvocationContext();
			if (context == null) {
				log.debug("No invocation context found for cache: {}, key: {}, using default context", getName(), key);
				return createDefaultContext();
			}

			// 深度验证上下文完整性和一致性
			if (!isValidInvocationContext(context)) {
				log.warn("Invalid invocation context for cache: {}, key: {}, using default context", getName(), key);
				return createDefaultContext();
			}

			// 验证策略类型兼容性
			if (!isStrategyTypeSupported(context.fetchStrategy())) {
				log.warn("Unsupported strategy type {} for cache: {}, key: {}, falling back to AUTO",
						context.fetchStrategy(), getName(), key);
				return createEnhancedDefaultContext(context);
			}

			return context;
		} catch (Exception e) {
			log.error("Error retrieving context for cache: {}, key: {}, using default context. Error: {}",
					getName(), key, e.getMessage(), e);
			return createDefaultContext();
		}
	}

	private CachedInvocationContext createDefaultContext() {
		return CachedInvocationContext.builder()
				.cacheNames(new String[]{getName()})
				.key("default")
				.condition("")
				.sync(false)
				.value(new String[]{})
				.keyGenerator("")
				.cacheManager("")
				.cacheResolver("")
				.unless("")
				.ttl(0L)
				.type(Object.class)
				.useSecondLevelCache(false)
				.distributedLock(false)
				.distributedLockName("")
				.internalLock(false)
				.cacheNullValues(false)
				.useBloomFilter(false)
				.randomTtl(false)
				.variance(0.0f)
				.fetchStrategy(CachedInvocationContext.FetchStrategyType.AUTO) // 使用AUTO策略
				.enablePreRefresh(false)
				.preRefreshThreshold(0.3)
				.customStrategyClass("")
				.build();
	}

	private boolean isValidInvocationContext(CachedInvocationContext context) {
		if (context == null) {
			return false;
		}

		// 基础属性验证
		if (context.cacheNames() == null || context.cacheNames().length == 0) {
			log.debug("Invalid context: missing cache names");
			return false;
		}

		if (context.fetchStrategy() == null) {
			log.debug("Invalid context: missing fetch strategy");
			return false;
		}

		// 数值范围验证
		if (context.variance() < 0 || context.variance() > 1) {
			log.debug("Invalid context: variance {} out of range [0,1]", context.variance());
			return false;
		}

		if (context.preRefreshThreshold() < 0 || context.preRefreshThreshold() > 1) {
			log.debug("Invalid context: preRefreshThreshold {} out of range [0,1]", context.preRefreshThreshold());
			return false;
		}

		// 逻辑一致性验证
		if (context.enablePreRefresh() && context.ttl() <= 0) {
			log.debug("Invalid context: preRefresh enabled but TTL <= 0");
			return false;
		}

		if (context.randomTtl() && context.variance() <= 0) {
			log.debug("Invalid context: randomTtl enabled but variance <= 0");
			return false;
		}

		// 锁配置验证
		if (context.distributedLock() &&
			(context.distributedLockName() == null || context.distributedLockName().trim().isEmpty())) {
			log.debug("Invalid context: distributedLock enabled but no lock name specified");
		}

		return true;
	}

	private boolean isStrategyTypeSupported(CachedInvocationContext.FetchStrategyType strategyType) {
		if (strategyType == null) {
			return false;
		}

		// 检查是否有支持该策略类型的实现
		List<CacheFetchStrategy> supportedStrategies = strategyManager.getAllStrategies();
		return supportedStrategies.stream()
				.anyMatch(strategy -> strategy.isStrategyTypeCompatible(strategyType));
	}

	private CachedInvocationContext createEnhancedDefaultContext(CachedInvocationContext originalContext) {
		// 基于原始上下文创建增强的默认上下文，保留有效配置
		return CachedInvocationContext.builder()
				.cacheNames(originalContext.cacheNames() != null ? originalContext.cacheNames() : new String[]{getName()})
				.key(originalContext.key() != null ? originalContext.key() : "default")
				.condition(originalContext.condition() != null ? originalContext.condition() : "")
				.sync(originalContext.sync())
				.value(originalContext.value() != null ? originalContext.value() : new String[]{})
				.keyGenerator(originalContext.keyGenerator() != null ? originalContext.keyGenerator() : "")
				.cacheManager(originalContext.cacheManager() != null ? originalContext.cacheManager() : "")
				.cacheResolver(originalContext.cacheResolver() != null ? originalContext.cacheResolver() : "")
				.unless(originalContext.unless() != null ? originalContext.unless() : "")
				.ttl(originalContext.ttl() > 0 ? originalContext.ttl() : 0L)
				.type(originalContext.type() != null ? originalContext.type() : Object.class)
				.useSecondLevelCache(originalContext.useSecondLevelCache())
				.distributedLock(originalContext.distributedLock())
				.distributedLockName(originalContext.distributedLockName() != null ? originalContext.distributedLockName() : "")
				.internalLock(originalContext.internalLock())
				.cacheNullValues(originalContext.cacheNullValues())
				.useBloomFilter(originalContext.useBloomFilter())
				.randomTtl(originalContext.randomTtl())
				.variance(Math.max(0.0f, Math.min(1.0f, originalContext.variance())))
				.fetchStrategy(CachedInvocationContext.FetchStrategyType.AUTO) // 强制使用AUTO策略
				.enablePreRefresh(originalContext.enablePreRefresh() && originalContext.ttl() > 0)
				.preRefreshThreshold(Math.max(0.0, Math.min(1.0, originalContext.preRefreshThreshold())))
				.customStrategyClass(originalContext.customStrategyClass() != null ? originalContext.customStrategyClass() : "")
				.build();
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
		// 首先调用父类获取基础缓存值
		ValueWrapper baseValue = super.get(key);

		// 获取当前缓存调用的上下文信息
		CachedInvocation invocation = registry.get(getName(), key).orElse(null);
		if (invocation == null || invocation.getCachedInvocationContext() == null) {
			// 如果没有调用上下文，直接返回值（可能是通过其他方式如@Cacheable访问的）
			log.debug("No invocation context found for cache: {}, key: {}, returning value as-is",
					getName(), key);
			return baseValue;
		}

		// 验证调用上下文有效性
		CachedInvocationContext invocationContext = invocation.getCachedInvocationContext();
		if (!isValidInvocationContext(invocationContext)) {
			log.warn("Invalid invocation context for cache: {}, key: {}, returning base value", getName(), key);
			return baseValue;
		}

		// 检查是否需要执行策略（某些情况下可以跳过策略执行）
		if (!shouldExecuteStrategies(invocationContext, baseValue)) {
			log.debug("Skipping strategy execution for cache: {}, key: {}", getName(), key);
			return baseValue;
		}

		// 准备策略执行上下文
		String cacheKey = createCacheKey(key);
		CacheFetchStrategy.CacheFetchCallback callback = createFetchCallback(invocation);
		CacheFetchStrategy.CacheFetchContext context = createFetchContext(key, cacheKey, baseValue, invocation, callback);

		if (!isValidFetchContext(context)) {
			log.warn("Invalid fetch context for cache: {}, key: {}, returning base value", getName(), key);
			return baseValue;
		}

		// 使用策略管理器执行获取策略
		return executeStrategiesWithFallback(context, baseValue, key);
	}

	// TTL、刷新、包装等方法已下沉到 CacheOperationService

	@Override
	public void put(@NonNull Object key, @Nullable Object value) {
		// 获取用户配置的上下文信息，避免重复的TTL随机化
		CachedInvocationContext invocationContext = getInvocationContext(key);
		Object toStore = cacheOperationService.wrapIfMataAbsent(value, key, cacheConfiguration, invocationContext);
		super.put(key, toStore);
		// 成功写入后将 key 加入布隆过滤器（值非空时）
		try {
			if (value != null) {
				String membershipKey = String.valueOf(key);
				cachePenetration.addIfEnabled(getName(), membershipKey);
			}
		} catch (Exception ignore) {
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
			} catch (Exception ignore) {
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
		ReentrantLock localLock = registry.obtainLock(getName(), key);
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
								} catch (Exception ignore) {
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
		ReentrantLock localLock = evictRegistry.obtainLock(getName(), key);
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
		ReentrantLock localLock = evictRegistry.obtainLock(getName(), "*");
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

	private CacheFetchStrategy.CacheFetchCallback createFetchCallback(CachedInvocation invocation) {
		return new CacheFetchStrategy.CacheFetchCallback() {
			@Override
			public ValueWrapper getBaseValue(Object key) {
				return RedisProCache.super.get(key);
			}

			@Override
			public void refresh(CachedInvocation invocation, Object key, String cacheKey, long ttl) {
				try {
					CacheOperationService.CacheRefreshCallback refreshCallback = new CacheOperationService.CacheRefreshCallback() {
						@Override
						public void putCache(Object key, Object value) {
							RedisProCache.this.put(key, value);
						}

						@Override
						public String getCacheName() {
							return getName();
						}
					};
					cacheOperationService.doRefresh(invocation, key, cacheKey, ttl, refreshCallback);
				} catch (Exception e) {
					log.error("Cache refresh failed for cache: {}, key: {}, error: {}",
							getName(), key, e.getMessage(), e);
				}
			}

			@Override
			public long resolveConfiguredTtlSeconds(Object value, Object key) {
				try {
					return cacheOperationService.resolveConfiguredTtlSeconds(value, key, cacheConfiguration);
				} catch (Exception e) {
					log.warn("Failed to resolve TTL for cache: {}, key: {}, using default", getName(), key);
					return -1L;
				}
			}

			@Override
			public boolean shouldPreRefresh(long ttl, long configuredTtl) {
				try {
					return cacheOperationService.shouldPreRefresh(ttl, configuredTtl);
				} catch (Exception e) {
					log.debug("Pre-refresh check failed for cache: {}, defaulting to false", getName());
					return false;
				}
			}
		};
	}

	private CacheFetchStrategy.CacheFetchContext createFetchContext(Object key, String cacheKey, ValueWrapper valueWrapper,
	                                                                CachedInvocation invocation, CacheFetchStrategy.CacheFetchCallback callback) {
		return new CacheFetchStrategy.CacheFetchContext(
				getName(),
				key,
				cacheKey,
				valueWrapper,
				invocation,
				invocation.getCachedInvocationContext(),
				redisTemplate,
				callback
		);
	}

	private boolean isValidFetchContext(CacheFetchStrategy.CacheFetchContext context) {
		return context != null
				&& context.cacheName() != null
				&& context.key() != null
				&& context.cacheKey() != null
				&& context.redisTemplate() != null
				&& context.callback() != null;
	}

	private ValueWrapper executeStrategiesWithFallback(CacheFetchStrategy.CacheFetchContext context,
	                                                   ValueWrapper fallbackValue, Object key) {
		long startTime = System.currentTimeMillis();
		String operationId = generateOperationId(key);

		try {
			log.debug("[{}] Starting strategy execution for cache: {}, key: {}", operationId, getName(), key);

			// 验证策略管理器状态
			if (strategyManager == null) {
				log.error("[{}] Strategy manager is null, using fallback", operationId);
				return fallbackValue;
			}

			// 执行策略链
			ValueWrapper result = strategyManager.fetch(context);
			long duration = System.currentTimeMillis() - startTime;

			if (result != null) {
				log.debug("[{}] Strategy execution successful in {}ms for cache: {}, key: {}",
						operationId, duration, getName(), key);
				return result;
			} else {
				log.debug("[{}] Strategy execution returned null in {}ms for cache: {}, key: {}, using fallback",
						operationId, duration, getName(), key);
				return handleNullResult(context, fallbackValue, operationId);
			}

		} catch (IllegalStateException e) {
			long duration = System.currentTimeMillis() - startTime;
			log.warn("[{}] Strategy configuration error in {}ms for cache: {}, key: {}: {}, using fallback",
					operationId, duration, getName(), key, e.getMessage());
			return fallbackValue;

		} catch (SecurityException e) {
			long duration = System.currentTimeMillis() - startTime;
			log.error("[{}] Security error in {}ms for cache: {}, key: {}: {}, using fallback",
					operationId, duration, getName(), key, e.getMessage());
			return fallbackValue;

		} catch (Exception e) {
			long duration = System.currentTimeMillis() - startTime;
			log.error("[{}] Strategy execution failed in {}ms for cache: {}, key: {}: {}, using fallback",
					operationId, duration, getName(), key, e.getMessage(), e);

			// 对于严重错误，触发断路器逻辑
			if (isCriticalError(e)) {
				handleCriticalError(context, e, operationId);
			}

			return fallbackValue;
		}
	}

	/**
	 * 处理空结果的情况
	 */
	private ValueWrapper handleNullResult(CacheFetchStrategy.CacheFetchContext context,
	                                      ValueWrapper fallbackValue, String operationId) {
		CachedInvocationContext invocationContext = context.invocationContext();

		// 如果配置了缓存空值，空结果可能是有效的
		if (invocationContext.cacheNullValues()) {
			log.debug("[{}] Null result accepted due to cacheNullValues=true", operationId);
			return null;
		}

		// 如果没有配置缓存空值，使用fallback
		return fallbackValue;
	}

	/**
	 * 判断是否为严重错误
	 */
	private boolean isCriticalError(Exception e) {
		// 检查错误原因是否为严重的Error类型
		Throwable cause = e.getCause();
		if (cause instanceof OutOfMemoryError || cause instanceof StackOverflowError) {
			return true;
		}

		// 检查错误消息中的关键字
		String message = e.getMessage();
		if (message != null) {
			String lowerMessage = message.toLowerCase();
			return lowerMessage.contains("redis connection")
					|| lowerMessage.contains("timeout")
					|| lowerMessage.contains("connection refused")
					|| lowerMessage.contains("pool exhausted")
					|| lowerMessage.contains("out of memory")
					|| lowerMessage.contains("too many connections");
		}

		// 检查特定异常类型
		return e instanceof java.util.concurrent.TimeoutException
				|| e instanceof java.net.ConnectException
				|| e instanceof java.io.IOException;
	}

	/**
	 * 处理严重错误
	 */
	private void handleCriticalError(CacheFetchStrategy.CacheFetchContext context, Exception e, String operationId) {
		log.error("[{}] Critical error detected in cache strategy execution: {}", operationId, e.getMessage());

		// 可以在这里添加断路器、降级逻辑等
		// 例如：临时禁用某些策略、发送告警等
		try {
			// 清理可能的资源泄露
			if (context.redisTemplate() != null) {
				// 可以添加连接池状态检查等
			}
		} catch (Exception cleanupError) {
			log.error("[{}] Error during critical error cleanup: {}", operationId, cleanupError.getMessage());
		}
	}

	/**
	 * 生成操作ID用于追踪
	 */
	/**
	 * 判断是否需要执行策略
	 */
	private boolean shouldExecuteStrategies(CachedInvocationContext invocationContext, ValueWrapper baseValue) {
		// 如果配置了 SIMPLE 策略且有缓存值，通常不需要执行其他策略
		if (invocationContext.fetchStrategy() == CachedInvocationContext.FetchStrategyType.SIMPLE) {
			// SIMPLE 策略主要用于日志和基础处理，在有值的情况下可以跳过
			return baseValue == null || invocationContext.cacheNullValues();
		}

		// 如果启用了布隆过滤器，需要执行策略（无论是否有值）
		if (invocationContext.useBloomFilter()) {
			return true;
		}

		// 如果启用了预刷新，需要执行策略检查 TTL
		if (invocationContext.enablePreRefresh() && baseValue != null) {
			return true;
		}

		// 如果没有缓存值，需要执行策略（特别是布隆过滤器检查）
		if (baseValue == null) {
			return true;
		}

		// 如果配置了分布式锁或内部锁，可能需要策略处理
		if (invocationContext.distributedLock() || invocationContext.internalLock()) {
			return true;
		}

		// 其他情况下，有值就不需要执行策略
		return false;
	}

	/**
	 * 获取调用上下文信息（用于TTL处理优化）
	 */
	private CachedInvocationContext getInvocationContext(Object key) {
		try {
			CachedInvocation invocation = registry.get(getName(), key).orElse(null);
			return invocation != null ? invocation.getCachedInvocationContext() : null;
		} catch (Exception e) {
			log.debug("Failed to get invocation context for key: {}, error: {}", key, e.getMessage());
			return null;
		}
	}

	private String generateOperationId(Object key) {
		return String.format("%s-%d", String.valueOf(key).hashCode(), System.currentTimeMillis() % 10000);
	}

	/**
	 * 获取缓存策略执行状态（用于监控）
	 */
	public String getStrategyStatus() {
		try {
			return strategyManager.getStrategyInfo();
		} catch (Exception e) {
			return "Strategy status unavailable: " + e.getMessage();
		}
	}

	/**
	 * 获取详细的缓存监控信息
	 */
	public String getCacheMonitoringInfo() {
		StringBuilder info = new StringBuilder();
		info.append("=== Cache Monitoring Info for ").append(getName()).append(" ===\n");

		// 基础信息
		info.append("Cache Name: ").append(getName()).append("\n");
		info.append("Cache Configuration: ").append(cacheConfiguration != null ? "Present" : "Missing").append("\n");
		info.append("Redis Template: ").append(redisTemplate != null ? "Active" : "Inactive").append("\n");

		// 依赖组件状态
		info.append("\n--- Component Status ---\n");
		info.append("Registry: ").append(registry != null ? "Active" : "Inactive").append("\n");
		info.append("Evict Registry: ").append(evictRegistry != null ? "Active" : "Inactive").append("\n");
		info.append("Distributed Lock: ").append(distributedLock != null ? "Available" : "Unavailable").append("\n");
		info.append("Cache Penetration: ").append(cachePenetration != null ? "Enabled" : "Disabled").append("\n");
		info.append("Cache Breakdown: ").append(cacheBreakdown != null ? "Enabled" : "Disabled").append("\n");
		info.append("Strategy Manager: ").append(strategyManager != null ? "Active" : "Inactive").append("\n");
		info.append("Cache Operation Service: ").append(cacheOperationService != null ? "Active" : "Inactive").append("\n");

		// 策略信息
		if (strategyManager != null) {
			info.append("\n--- Strategy Information ---\n");
			try {
				info.append(strategyManager.getStrategyInfo());
			} catch (Exception e) {
				info.append("Strategy info error: ").append(e.getMessage()).append("\n");
			}
		}

		// 配置详情
		if (cacheConfiguration != null) {
			info.append("\n--- Cache Configuration ---\n");
			try {
				info.append("TTL: ").append(cacheConfiguration.getTtl()).append("\n");
				info.append("Key Prefix: ").append(cacheConfiguration.getKeyPrefixFor(getName())).append("\n");
				info.append("Cache Null Values: ").append(cacheConfiguration.getAllowCacheNullValues()).append("\n");
				info.append("Use Key Prefix: ").append(cacheConfiguration.usePrefix()).append("\n");
			} catch (Exception e) {
				info.append("Configuration details error: ").append(e.getMessage()).append("\n");
			}
		}

		return info.toString();
	}

	/**
	 * 获取上下文使用统计（简化版监控）
	 */
	public String getContextUsageStats() {
		StringBuilder stats = new StringBuilder();
		stats.append("=== Context Usage Statistics ===\n");

		try {
			// 统计当前注册的调用
			if (registry != null) {
				stats.append("Active Invocations: Available\n");
				// 这里可以添加更详细的统计，如果registry支持的话
			} else {
				stats.append("Active Invocations: Registry unavailable\n");
			}

			// 策略兼容性检查
			if (strategyManager != null) {
				List<CacheFetchStrategy> strategies = strategyManager.getAllStrategies();
				int compatibleStrategies = 0;

				for (CacheFetchStrategy strategy : strategies) {
					try {
						// 创建测试上下文检查兼容性
						CachedInvocationContext testContext = createDefaultContext();
						if (strategy.isStrategyTypeCompatible(testContext.fetchStrategy())) {
							compatibleStrategies++;
						}
					} catch (Exception e) {
						// 忽略检查错误
					}
				}

				stats.append("Total Strategies: ").append(strategies.size()).append("\n");
				stats.append("Compatible Strategies: ").append(compatibleStrategies).append("\n");
			}

		} catch (Exception e) {
			stats.append("Statistics collection error: ").append(e.getMessage()).append("\n");
		}

		return stats.toString();
	}

	/**
	 * 健康检查方法
	 */
	public boolean isHealthy() {
		try {
			// 检查核心组件
			if (strategyManager == null || cacheOperationService == null) {
				return false;
			}

			// 检查Redis连接
			if (redisTemplate == null) {
				return false;
			}

			// 检查策略可用性
			List<CacheFetchStrategy> strategies = strategyManager.getAllStrategies();
			if (strategies.isEmpty()) {
				return false;
			}

			// 简单的Redis连通性测试
			try {
				redisTemplate.hasKey("health-check-key");
			} catch (Exception e) {
				log.warn("Redis connectivity check failed: {}", e.getMessage());
				return false;
			}

			return true;

		} catch (Exception e) {
			log.error("Health check failed: {}", e.getMessage());
			return false;
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
				registry.remove(getName(), key);
			} catch (Exception ignore) {
			}
			try {
				evictRegistry.remove(getName(), key);
			} catch (Exception ignore) {
			}
		}
	}

	/** 延迟二次删除（单键），并在二次删除时再次尝试加锁以避免竞态。 */
	private void scheduleSecondDeleteForKey(@NonNull Object key) {
		Executor delayed = CompletableFuture.delayedExecutor(
				DOUBLE_DELETE_DELAY_MS, TimeUnit.MILLISECONDS, executor);
		CompletableFuture.runAsync(
				() -> {
					String cacheKey = createCacheKey(key);
					String distKey = "cache:evict:" + cacheKey;
					ReentrantLock localLock = evictRegistry.obtainLock(getName(), key);
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
				registry.removeAll(getName());
			} catch (Exception ignore) {
			}
			try {
				evictRegistry.removeAll(getName());
			} catch (Exception ignore) {
			}
		}
	}

	/** 延迟二次全量删除，并在二次删除时再次尝试加锁以避免竞态。 */
	private void scheduleSecondClear() {
		Executor delayed = CompletableFuture.delayedExecutor(
				DOUBLE_DELETE_DELAY_MS, TimeUnit.MILLISECONDS, executor);
		CompletableFuture.runAsync(
				() -> {
					String distKey = "cache:evictAll:" + getName();
					ReentrantLock localLock = evictRegistry.obtainLock(getName(), "*");
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
}
