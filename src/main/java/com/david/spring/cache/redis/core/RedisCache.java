package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.template.CacheOperationTemplate;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.concurrent.Callable;

@Slf4j
public class RedisCache extends AbstractValueAdaptingCache {

	private final String name;
	private final RedisTemplate<String, Object> redisTemplate;
	private final Duration defaultTtl;
	private final boolean allowNullValues;
	// 设置操作模板，由 RedisCacheManager 调用
	@Setter
	private CacheOperationTemplate operationTemplate;

	public RedisCache(String name, RedisTemplate<String, Object> redisTemplate,
	                  Duration defaultTtl, boolean allowNullValues) {
		super(allowNullValues);
		this.name = name;
		this.redisTemplate = redisTemplate;
		this.defaultTtl = defaultTtl;
		this.allowNullValues = allowNullValues;
	}


	@Override
	@NonNull
	public String getName() {
		return this.name;
	}

	@Override
	@NonNull
	public Object getNativeCache() {
		return this.redisTemplate;
	}

	@Override
	protected Object lookup(@NonNull Object key) {
		if (operationTemplate != null) {
			return operationTemplate.lookup(redisTemplate, name, key, allowNullValues);
		}
		// 兼容性处理：如果没有模板，抛出异常
		throw new UnsupportedOperationException("CacheOperationTemplate is required");
	}

	@Override
	public void put(@NonNull Object key, @Nullable Object value) {
		putWithTtl(key, value, defaultTtl);
	}

	public void putWithTtl(Object key, @Nullable Object value, Duration ttl) {
		if (operationTemplate != null) {
			operationTemplate.put(redisTemplate, name, key, value, ttl, allowNullValues);
		} else {
			// 兼容性处理：如果没有模板，抛出异常
			throw new UnsupportedOperationException("CacheOperationTemplate is required");
		}
	}

	@Override
	public ValueWrapper putIfAbsent(@NonNull Object key, @Nullable Object value) {
		if (operationTemplate != null) {
			return operationTemplate.putIfAbsent(redisTemplate, name, key, value, defaultTtl, allowNullValues);
		} else {
			// 兼容性处理：如果没有模板，抛出异常
			throw new UnsupportedOperationException("CacheOperationTemplate is required");
		}
	}

	@Override
	public void evict(@NonNull Object key) {
		if (operationTemplate != null) {
			operationTemplate.evict(redisTemplate, name, key);
		} else {
			// 兼容性处理：如果没有模板，抛出异常
			throw new UnsupportedOperationException("CacheOperationTemplate is required");
		}
	}

	@Override
	public void clear() {
		if (operationTemplate != null) {
			operationTemplate.clear(redisTemplate, name);
		} else {
			// 兼容性处理：如果没有模板，抛出异常
			throw new UnsupportedOperationException("CacheOperationTemplate is required");
		}
	}

	@Override
	public <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
		if (operationTemplate != null) {
			return operationTemplate.get(redisTemplate, name, key, valueLoader, allowNullValues);
		} else {
			// 兼容性处理：如果没有模板，抛出异常
			throw new UnsupportedOperationException("CacheOperationTemplate is required");
		}
	}

	/**
	 * 获取缓存统计信息
	 */
	public CacheStats getCacheStats(Object key) {
		String cacheKey = createCacheKey(key);
		try {
			Object rawValue = redisTemplate.opsForValue().get(cacheKey);
			if (rawValue instanceof CachedValue cachedValue) {
				return new CacheStats(
						cachedValue.getVisitTimes(),
						cachedValue.getAge(),
						cachedValue.getRemainingTtl(),
						cachedValue.getType()
				);
			}
			return null;
		} catch (Exception e) {
			log.warn("Failed to get cache stats for key '{}': {}", cacheKey, e.getMessage());
			return null;
		}
	}

	private String createCacheKey(Object key) {
		return name + ":" + key;
	}


	/**
	 * 缓存统计信息
	 */
	public record CacheStats(long visitTimes, long age, long remainingTtl, Class<?> valueType) {

		@Override
		@NonNull
		public String toString() {
			return String.format("CacheStats{visitTimes=%d, age=%ds, remainingTtl=%ds, valueType=%s}",
					visitTimes, age, remainingTtl, valueType.getSimpleName());
		}
	}
}