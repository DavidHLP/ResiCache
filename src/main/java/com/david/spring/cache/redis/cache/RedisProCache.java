package com.david.spring.cache.redis.cache;

import com.david.spring.cache.redis.config.CacheGuardProperties;
import com.david.spring.cache.redis.lock.DistributedLock;
import com.david.spring.cache.redis.lock.LockUtils;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.RegistryFactory;
import com.david.spring.cache.redis.chain.CacheHandlerChainBuilder;
import com.david.spring.cache.redis.chain.CacheHandlerChain;
import com.david.spring.cache.redis.chain.CacheHandlerContext;
import com.david.spring.cache.redis.cache.support.CacheOperationService;
import com.david.spring.cache.redis.cache.support.CacheContextValidator;
import com.david.spring.cache.redis.cache.support.CacheHandlerExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
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
	private final CacheHandlerChainBuilder chainBuilder;
	private final CacheOperationService cacheOperationService;
	private final CacheContextValidator contextValidator;
	private final CacheHandlerExecutor handlerExecutor;
	private final DistributedLock distributedLock;


	public RedisProCache(
			String name,
			RedisCacheWriter cacheWriter,
			RedisCacheConfiguration cacheConfiguration,
			RedisTemplate<String, Object> redisTemplate,
			RegistryFactory registryFactory,
			Executor executor,
			CacheHandlerChainBuilder chainBuilder,
			CacheOperationService cacheOperationService,
			CacheContextValidator contextValidator,
			CacheHandlerExecutor handlerExecutor,
			DistributedLock distributedLock,
			CacheGuardProperties properties) {
		super(name, cacheWriter, cacheConfiguration);
		this.redisTemplate = Objects.requireNonNull(redisTemplate);
		this.registryFactory = Objects.requireNonNull(registryFactory);
		this.executor = Objects.requireNonNull(executor);
		this.cacheConfiguration = Objects.requireNonNull(cacheConfiguration);
		this.chainBuilder = Objects.requireNonNull(chainBuilder);
		this.cacheOperationService = Objects.requireNonNull(cacheOperationService);
		this.contextValidator = Objects.requireNonNull(contextValidator);
		this.handlerExecutor = Objects.requireNonNull(handlerExecutor);
		this.distributedLock = Objects.requireNonNull(distributedLock);
		this.properties = Objects.requireNonNull(properties);
		// 验证处理器集成
		validateHandlerIntegration();
	}

	/**
	 * 获取缓存值，支持通过处理器链进行增强处理
	 */
	@Override
	@Nullable
	public ValueWrapper get(@NonNull Object key) {
		ValueWrapper baseValue = super.get(key);

		CachedInvocation invocation = findInvocation(key);
		if (invocation == null) {
			return baseValue;
		}

		if (!shouldExecuteHandlerChain(invocation, baseValue, key)) {
			return baseValue;
		}

		return executeHandlerChain(key, baseValue, invocation);
	}

	// TTL、刷新、包装等方法已下沉到 CacheOperationService

	/**
	 * 缓存数据，支持TTL随机化和雪崩保护
	 */
	@Override
	public void put(@NonNull Object key, @Nullable Object value) {
		CachedInvocationContext invocationContext = getInvocationContext(key);
		Object toStore = cacheOperationService.wrapIfMataAbsent(value, key, cacheConfiguration, invocationContext);
		super.put(key, toStore);

		String cacheKey = createCacheKey(key);
		cacheOperationService.applyLitteredExpire(key, toStore, cacheKey, redisTemplate);
	}

	/**
	 * 仅当缓存中不存在时才缓存数据
	 */
	@Override
	@NonNull
	public ValueWrapper putIfAbsent(@NonNull Object key, @Nullable Object value) {
		CachedInvocationContext invocationContext = getInvocationContext(key);
		Object toStore = cacheOperationService.wrapIfMataAbsent(value, key, cacheConfiguration, invocationContext);
		String cacheKey = createCacheKey(key);
		boolean firstInsert = cacheOperationService.wasKeyAbsentBeforePut(cacheKey, redisTemplate);
		ValueWrapper previous = super.putIfAbsent(key, toStore);
		if (firstInsert) {
			cacheOperationService.applyLitteredExpire(key, toStore, cacheKey, redisTemplate);
		}
		return previous;
	}

	/**
	 * 获取指定类型的缓存值
	 */
	@Override
	@Nullable
	public <T> T get(@NonNull Object key, @Nullable Class<T> type) {
		ValueWrapper wrapper = this.get(key);
		if (wrapper == null) {
			return null;
		}
		Object val = wrapper.get();
		if (type == null || type == Object.class) {
			@SuppressWarnings("unchecked")
			T casted = (T) val;
			return casted;
		}
		return (type.isInstance(val) ? type.cast(val) : null);
	}

	/**
	 * 获取缓存值，如果不存在则通过valueLoader加载
	 */
	@Override
	@NonNull
	public <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
		ValueWrapper cached = this.get(key);
		if (cached != null) {
			@SuppressWarnings("unchecked")
			T value = (T) cached.get();
			if (value != null) {
				return value;
			}
		}

		try {
			T loadedValue = valueLoader.call();
			if (loadedValue != null) {
				this.put(key, loadedValue);
			}
			return loadedValue;
		} catch (Exception e) {
			throw new Cache.ValueRetrievalException(key, valueLoader, e);
		}
	}

	/**
	 * 删除缓存项，支持通过处理器链进行增强处理
	 */
	@Override
	public void evict(@NonNull Object key) {
		CachedInvocation invocation = findInvocation(key);
		if (invocation == null) {
			fallbackEvict(key);
			return;
		}

		try {
			executeEvictHandlerChain(key, invocation);
			log.debug("Evict operation completed through handler chain: cache={}, key={}", getName(), key);
		} catch (Exception e) {
			log.error("Handler chain evict failed, falling back to direct eviction: cache={}, key={}, error={}",
					getName(), key, e.getMessage(), e);
			fallbackEvict(key);
		}
	}

	/**
	 * 清空缓存，支持通过处理器链进行增强处理
	 */
	@Override
	public void clear() {
		try {
			CachedInvocation dummyInvocation = createDummyInvocationForClear();
			if (dummyInvocation == null) {
				log.debug("Unable to create dummy invocation for clear, using traditional approach: cache={}", getName());
				fallbackClear();
				return;
			}

			executeClearHandlerChain(dummyInvocation);
			log.debug("Clear operation completed through handler chain: cache={}", getName());
		} catch (Exception e) {
			log.error("Handler chain clear failed, falling back to direct clear: cache={}, error={}",
					getName(), e.getMessage(), e);
			fallbackClear();
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

	/**
	 * 立即删除单键并清理本地注册
	 */
	private void doEvictInternal(@NonNull Object key) {
		try {
			getCacheWriter().remove(getName(), createAndConvertCacheKey(key));
		} finally {
			cleanupRegistries(key);
		}
	}

	/**
	 * 延迟二次删除（单键），并在二次删除时再次尝试加锁以避免竞态
	 */
	private void scheduleSecondDeleteForKey(@NonNull Object key) {
		Executor delayed = CompletableFuture.delayedExecutor(
				properties.getDoubleDeleteDelayMs(), TimeUnit.MILLISECONDS, executor);
		CompletableFuture.runAsync(
				() -> executeWithLock(key, () -> doEvictInternal(key)),
				delayed);
	}

	/**
	 * 立即全量清理并清理本地注册
	 */
	private void doClearInternal() {
		try {
			super.clear();
		} finally {
			cleanupAllRegistries();
		}
	}

	/**
	 * 延迟二次全量删除，并在二次删除时再次尝试加锁以避免竞态
	 */
	private void scheduleSecondClear() {
		Executor delayed = CompletableFuture.delayedExecutor(
				properties.getDoubleDeleteDelayMs(), TimeUnit.MILLISECONDS, executor);
		CompletableFuture.runAsync(
				() -> executeWithLock("*", this::doClearInternal),
				delayed);
	}

	/**
	 * 为clear操作创建虚拟的调用信息。
	 *
	 * @return 虚拟的缓存调用信息
	 */
	private CachedInvocation createDummyInvocationForClear() {
		try {
			// 创建一个最小配置的调用上下文用于clear操作
			CachedInvocationContext dummyContext = CachedInvocationContext.builder()
					.cacheNullValues(false)
					.enablePreRefresh(false)
					.preRefreshThreshold(0.3)
					.build();

			return CachedInvocation.builder()
					.cachedInvocationContext(dummyContext)
					.build();
		} catch (Exception e) {
			log.warn("Failed to create dummy invocation for clear operation: {}", e.getMessage());
			// 如果创建失败，返回null，调用方会降级到传统方式
			return null;
		}
	}

	/**
	 * 查找缓存调用信息
	 */
	private CachedInvocation findInvocation(Object key) {
		try {
			return registryFactory.getCacheInvocationRegistry().get(getName(), key).orElse(null);
		} catch (Exception e) {
			log.debug("Failed to find invocation for cache: {}, key: {}", getName(), key, e);
			return null;
		}
	}

	/**
	 * 判断是否应该执行处理器链
	 */
	private boolean shouldExecuteHandlerChain(CachedInvocation invocation, ValueWrapper baseValue, Object key) {
		if (invocation == null || invocation.getCachedInvocationContext() == null) {
			log.debug("No invocation context found for cache: {}, key: {}, returning value as-is", getName(), key);
			return false;
		}

		CachedInvocationContext invocationContext = invocation.getCachedInvocationContext();
		if (!contextValidator.isValidInvocationContext(invocationContext)) {
			log.warn("Invalid invocation context for cache: {}, key: {}, returning base value", getName(), key);
			return false;
		}

		if (!contextValidator.shouldExecuteHandlers(invocationContext, baseValue)) {
			log.debug("Skipping handler chain execution for cache: {}, key: {}", getName(), key);
			return false;
		}

		return true;
	}

	/**
	 * 执行处理器链进行缓存获取
	 */
	private ValueWrapper executeHandlerChain(Object key, ValueWrapper baseValue, CachedInvocation invocation) {
		String cacheKey = createCacheKey(key);
		CacheHandlerContext.CacheFetchCallback callback = handlerExecutor.createFetchCallback(getName(), this, cacheOperationService);
		CacheHandlerContext context = handlerExecutor.createHandlerContext(
				getName(), key, cacheKey, baseValue, invocation, redisTemplate, callback);

		if (!contextValidator.isValidHandlerContext(context)) {
			log.warn("Invalid handler context for cache: {}, key: {}, returning base value", getName(), key);
			return baseValue;
		}

		return handlerExecutor.executeHandlersWithFallback(context, baseValue, key, getName());
	}

	/**
	 * 执行删除操作的处理器链
	 */
	private void executeEvictHandlerChain(Object key, CachedInvocation invocation) {
		String cacheKey = createCacheKey(key);
		CacheHandlerContext.CacheFetchCallback callback = handlerExecutor.createFetchCallback(getName(), this, cacheOperationService);
		CacheHandlerContext context = handlerExecutor.createHandlerContext(
				getName(), key, cacheKey, null, invocation, redisTemplate, callback,
				com.david.spring.cache.redis.chain.CacheOperationType.EVICT);

		handlerExecutor.executeHandlersWithFallback(context, null, key, getName());
	}

	/**
	 * 执行清空操作的处理器链
	 */
	private void executeClearHandlerChain(CachedInvocation dummyInvocation) {
		String cacheKey = createCacheKey("*");
		CacheHandlerContext.CacheFetchCallback callback = handlerExecutor.createFetchCallback(getName(), this, cacheOperationService);
		CacheHandlerContext context = handlerExecutor.createHandlerContext(
				getName(), "*", cacheKey, null, dummyInvocation, redisTemplate, callback,
				com.david.spring.cache.redis.chain.CacheOperationType.CLEAR);

		handlerExecutor.executeHandlersWithFallback(context, null, "*", getName());
	}

	/**
	 * 降级删除操作
	 */
	private void fallbackEvict(Object key) {
		doEvictInternal(key);
		scheduleSecondDeleteForKey(key);
	}

	/**
	 * 降级清空操作
	 */
	private void fallbackClear() {
		doClearInternal();
		scheduleSecondClear();
	}

	/**
	 * 执行带锁的操作
	 */
	private void executeWithLock(Object key, Runnable operation) {
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
				operation);
	}

	/**
	 * 清理注册表项
	 */
	private void cleanupRegistries(Object key) {
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

	/**
	 * 清理所有注册表项
	 */
	private void cleanupAllRegistries() {
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

	private void validateHandlerIntegration() {
		try {
			List<String> handlers = chainBuilder.getAvailableHandlers();
			if (handlers.isEmpty()) {
				log.warn("No handlers registered in cache: {}", getName());
				return;
			}

			log.info("Handler integration validation for cache: {} - {} handlers registered",
					getName(), handlers.size());

			// 验证是否有默认处理器
			boolean hasDefaultHandler = handlers.contains("Simple");

			if (!hasDefaultHandler) {
				log.warn("No default Simple handler found for cache: {}", getName());
			}

			// 输出处理器信息
			if (log.isDebugEnabled()) {
				Map<String, Object> stats = chainBuilder.getCacheStats();
				log.debug("Handler configuration for cache {}: {}", getName(), stats);
			}

		} catch (Exception e) {
			log.error("Handler integration validation failed for cache: {}, error: {}", getName(), e.getMessage(), e);
		}
	}

}
