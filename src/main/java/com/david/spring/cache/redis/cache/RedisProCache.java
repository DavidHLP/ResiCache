package com.david.spring.cache.redis.cache;

import com.david.spring.cache.redis.config.CacheGuardProperties;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.chain.CacheHandlerContext;
import com.david.spring.cache.redis.cache.support.CacheOperationService;
import com.david.spring.cache.redis.cache.support.CacheHandlerService;
import com.david.spring.cache.redis.cache.support.CacheRegistryService;
import com.david.spring.cache.redis.cache.support.CacheAsyncOperationService;
import com.david.spring.cache.redis.cache.support.CacheHandlerExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Objects;
import java.util.concurrent.Callable;

@Slf4j
public class RedisProCache extends RedisCache {

	private final RedisTemplate<String, Object> redisTemplate;
	private final RedisCacheConfiguration cacheConfiguration;
	private final CacheOperationService cacheOperationService;
	private final CacheHandlerService handlerService;
	private final CacheRegistryService registryService;
	private final CacheAsyncOperationService asyncOperationService;
	private final CacheHandlerExecutor handlerExecutor;


	public RedisProCache(
			String name,
			RedisCacheWriter cacheWriter,
			RedisCacheConfiguration cacheConfiguration,
			RedisTemplate<String, Object> redisTemplate,
			CacheOperationService cacheOperationService,
			CacheHandlerService handlerService,
			CacheRegistryService registryService,
			CacheAsyncOperationService asyncOperationService,
			CacheHandlerExecutor handlerExecutor) {
		super(name, cacheWriter, cacheConfiguration);
		this.redisTemplate = Objects.requireNonNull(redisTemplate);
		this.cacheConfiguration = Objects.requireNonNull(cacheConfiguration);
		this.cacheOperationService = Objects.requireNonNull(cacheOperationService);
		this.handlerService = Objects.requireNonNull(handlerService);
		this.registryService = Objects.requireNonNull(registryService);
		this.asyncOperationService = Objects.requireNonNull(asyncOperationService);
		this.handlerExecutor = Objects.requireNonNull(handlerExecutor);
	}

	/**
	 * 获取缓存值，支持通过处理器链进行增强处理
	 */
	@Override
	@Nullable
	public ValueWrapper get(@NonNull Object key) {
		ValueWrapper baseValue = super.get(key);

		CachedInvocation invocation = registryService.findInvocation(getName(), key);
		if (invocation == null || !handlerService.shouldExecuteHandlerChain(invocation, baseValue, getName(), key)) {
			return baseValue;
		}

		String cacheKey = createCacheKey(key);
		CacheHandlerContext.CacheFetchCallback callback = handlerExecutor.createFetchCallback(getName(), this, cacheOperationService);
		return handlerService.executeHandlerChain(getName(), key, cacheKey, baseValue, invocation, redisTemplate, callback);
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
		CachedInvocation invocation = registryService.findInvocation(getName(), key);
		if (invocation == null) {
			fallbackEvict(key);
			return;
		}

		try {
			String cacheKey = createCacheKey(key);
			CacheHandlerContext.CacheFetchCallback callback = handlerExecutor.createFetchCallback(getName(), this, cacheOperationService);
			handlerService.executeEvictHandlerChain(getName(), key, cacheKey, invocation, redisTemplate, callback);
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
			CachedInvocation dummyInvocation = handlerService.createDummyInvocationForClear();
			if (dummyInvocation == null) {
				log.debug("Unable to create dummy invocation for clear, using traditional approach: cache={}", getName());
				fallbackClear();
				return;
			}

			String cacheKey = createCacheKey("*");
			CacheHandlerContext.CacheFetchCallback callback = handlerExecutor.createFetchCallback(getName(), this, cacheOperationService);
			handlerService.executeClearHandlerChain(getName(), cacheKey, dummyInvocation, redisTemplate, callback);
			log.debug("Clear operation completed through handler chain: cache={}", getName());
		} catch (Exception e) {
			log.error("Handler chain clear failed, falling back to direct clear: cache={}, error={}",
					getName(), e.getMessage(), e);
			fallbackClear();
		}
	}

	private CachedInvocationContext getInvocationContext(Object key) {
		CachedInvocation invocation = registryService.findInvocation(getName(), key);
		return invocation != null ? invocation.getCachedInvocationContext() : null;
	}


	@Override
	protected Object fromStoreValue(@Nullable Object storeValue) {
		Object v = super.fromStoreValue(storeValue);
		return cacheOperationService.fromStoreValue(v);
	}

	// 过期时间设置和存在性检查方法已下沉到 CacheOperationService

	private void fallbackEvict(Object key) {
		byte[] serializedKey = serializeCacheKey(createCacheKey(key));
		String cacheKey = createCacheKey(key);
		asyncOperationService.doEvictInternal(getName(), key, getCacheWriter(), serializedKey);
		asyncOperationService.scheduleSecondDeleteForKey(getName(), key, getCacheWriter(), serializedKey, cacheKey);
	}

	private void fallbackClear() {
		String cacheKey = createCacheKey("*");
		asyncOperationService.doClearInternal(getName(), super::clear);
		asyncOperationService.scheduleSecondClear(getName(), cacheKey, super::clear);
	}


}
