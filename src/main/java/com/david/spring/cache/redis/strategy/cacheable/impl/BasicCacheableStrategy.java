package com.david.spring.cache.redis.strategy.cacheable.impl;

import com.david.spring.cache.redis.cache.RedisProCache;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.strategy.cacheable.CacheableStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

/**
 * 基础缓存策略实现
 * 提供标准的缓存操作，无特殊保护机制
 *
 * @author David
 */
@Slf4j
@Component
public class BasicCacheableStrategy implements CacheableStrategy<Object>, Ordered {

	private static final String STRATEGY_NAME = "BasicCacheableStrategy";
	private static final int PRIORITY = Ordered.LOWEST_PRECEDENCE;

	/**
	 * 执行缓存操作的通用方法，自动处理 RedisProCache 的父类调用
	 */
	private void executeVoidOperation(Cache cache, Object key, VoidCacheOperation operation) {
		if (cache instanceof RedisProCache redisProCache) {
			operation.executeOnRedisProCache(redisProCache, key);
		} else {
			operation.executeOnGenericCache(cache, key);
		}
	}

	/**
	 * 执行返回 ValueWrapper 的缓存操作
	 */
	private Cache.ValueWrapper executeValueWrapperOperation(Cache cache, Object key, Object value, ValueWrapperCacheOperation operation) {
		if (cache instanceof RedisProCache redisProCache) {
			return operation.executeOnRedisProCache(redisProCache, key, value);
		} else {
			return operation.executeOnGenericCache(cache, key, value);
		}
	}

	@Override
	@Nullable
	public Cache.ValueWrapper get(@NonNull Cache cache, @NonNull Object key, @NonNull CachedInvocation cachedInvocation) {
		log.debug("Getting cache value using basic strategy, cache={}, key={}", cache.getName(), key);
		return cache instanceof RedisProCache redisProCache
				? redisProCache.getFromParent(key)
				: cache.get(key);
	}

	@Override
	@NonNull
	public Object get(@NonNull Cache cache, @NonNull Object key, @NonNull Callable<Object> valueLoader, @NonNull CachedInvocation cachedInvocation) {
		log.debug("Getting cache value with loader using basic strategy, cache={}, key={}", cache.getName(), key);
		if (cache instanceof RedisProCache redisProCache) {
			return getFromRedisProCache(redisProCache, key, valueLoader);
		} else {
			return getFromGenericCache(cache, key, valueLoader);
		}
	}

	/**
	 * 从 RedisProCache 获取值，使用父类方法避免循环调用
	 * 自动处理 CacheMata 的包装和解包
	 */
	private Object getFromRedisProCache(RedisProCache cache, Object key, Callable<Object> valueLoader) {
		// 首先尝试从缓存获取（会自动解包 CacheMata）
		Cache.ValueWrapper existing = cache.getFromParent(key);
		if (existing != null) {
			Object value = existing.get();
			if (value != null) {
				log.debug("Cache hit for key: {}, returning cached value", key);
				return value;
			}
		}

		// 缓存不存在，使用 valueLoader 加载
		log.debug("Cache miss for key: {}, loading value with valueLoader", key);
		try {
			Object value = valueLoader.call();
			if (value != null) {
				// 存储时会自动包装为 CacheMata
				cache.putFromParent(key, value);
				log.debug("Value loaded and cached for key: {}", key);
				return value;
			}
			throw new RuntimeException("Value loader returned null");
		} catch (Exception e) {
			log.error("Value loader failed for key: {}", key, e);
			throw new RuntimeException("Value loader failed", e);
		}
	}

	/**
	 * 从通用 Cache 获取值
	 */
	private Object getFromGenericCache(Cache cache, Object key, Callable<Object> valueLoader) {
		try {
			Object result = cache.get(key, valueLoader);
			return result != null ? result : valueLoader.call();
		} catch (Exception e) {
			throw new RuntimeException("Cache operation failed", e);
		}
	}

	@Override
	public void put(@NonNull Cache cache, @NonNull Object key, @Nullable Object value, @NonNull CachedInvocation cachedInvocation) {
		log.debug("Putting cache value using basic strategy, cache={}, key={}", cache.getName(), key);
		executeVoidOperation(cache, key, new VoidCacheOperation() {
			@Override
			public void executeOnRedisProCache(RedisProCache cache, Object key) {
				cache.putFromParent(key, value);
			}

			@Override
			public void executeOnGenericCache(Cache cache, Object key) {
				cache.put(key, value);
			}
		});
	}

	@Override
	@NonNull
	public Cache.ValueWrapper putIfAbsent(@NonNull Cache cache, @NonNull Object key, @Nullable Object value, @NonNull CachedInvocation cachedInvocation) {
		log.debug("Putting cache value if absent using basic strategy, cache={}, key={}", cache.getName(), key);
		return executeValueWrapperOperation(cache, key, value, new ValueWrapperCacheOperation() {
			@Override
			public Cache.ValueWrapper executeOnRedisProCache(RedisProCache cache, Object key, Object value) {
				return cache.putIfAbsentFromParent(key, value);
			}

			@Override
			public Cache.ValueWrapper executeOnGenericCache(Cache cache, Object key, Object value) {
				Cache.ValueWrapper result = cache.putIfAbsent(key, value);
				if (result != null) {
					return result;
				}
				// If putIfAbsent returned null, it means the value was inserted, so get it back
				Cache.ValueWrapper newValue = cache.get(key);
				return newValue != null ? newValue : new SimpleValueWrapper(value);
			}
		});
	}

	@Override
	public void evict(@NonNull Cache cache, @NonNull Object key, @NonNull CachedInvocation cachedInvocation) {
		log.debug("Evicting cache value using basic strategy, cache={}, key={}", cache.getName(), key);
		executeVoidOperation(cache, key, new VoidCacheOperation() {
			@Override
			public void executeOnRedisProCache(RedisProCache cache, Object key) {
				cache.evictFromParent(key);
			}

			@Override
			public void executeOnGenericCache(Cache cache, Object key) {
				cache.evict(key);
			}
		});
	}

	@Override
	public void clear(@NonNull Cache cache, @NonNull CachedInvocation cachedInvocation) {
		log.debug("Clearing cache using basic strategy, cache={}", cache.getName());
		cache.clear();
	}

	@Override
	public boolean supports(@NonNull CachedInvocation cachedInvocation) {
		return true;
	}

	@Override
	@NonNull
	public String getStrategyName() {
		return STRATEGY_NAME;
	}

	@Override
	public int getPriority() {
		return PRIORITY;
	}

	@Override
	public int getOrder() {
		return PRIORITY;
	}

	/**
	 * 无返回值的缓存操作接口
	 */
	private interface VoidCacheOperation {
		void executeOnRedisProCache(RedisProCache cache, Object key);

		void executeOnGenericCache(Cache cache, Object key);
	}

	/**
	 * 返回 ValueWrapper 的缓存操作接口
	 */
	private interface ValueWrapperCacheOperation {
		Cache.ValueWrapper executeOnRedisProCache(RedisProCache cache, Object key, Object value);

		Cache.ValueWrapper executeOnGenericCache(Cache cache, Object key, Object value);
	}

	public record SimpleValueWrapper(Object value) implements Cache.ValueWrapper {

		@Override
		public Object get() {
			return value;
		}
	}
}