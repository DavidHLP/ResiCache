package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.event.*;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.concurrent.Callable;

@Slf4j
public class RedisCache extends AbstractValueAdaptingCache {

	private final String name;
	private final RedisTemplate<String, Object> redisTemplate;
	private final Duration defaultTtl;
	private final boolean allowNullValues;

	// 观察者模式支持
	private CacheEventPublisher eventPublisher;

	public RedisCache(String name, RedisTemplate<String, Object> redisTemplate,
	                  Duration defaultTtl, boolean allowNullValues) {
		super(allowNullValues);
		this.name = name;
		this.redisTemplate = redisTemplate;
		this.defaultTtl = defaultTtl;
		this.allowNullValues = allowNullValues;
	}

	/**
	 * 设置事件发布器（观察者模式支持）
	 */
	public void setEventPublisher(CacheEventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public Object getNativeCache() {
		return this.redisTemplate;
	}

	@Override
	protected Object lookup(Object key) {
		String cacheKey = createCacheKey(key);
		try {
			long startTime = System.currentTimeMillis();
			Object rawValue = redisTemplate.opsForValue().get(cacheKey);

			if (rawValue == null) {
				log.debug("Cache miss for key '{}'", cacheKey);
				publishCacheMissEvent(cacheKey, "not_found");
				return null;
			}

			// 检查是否是 CachedValue 类型
			if (rawValue instanceof CachedValue cachedValue) {
				// 检查是否过期
				if (cachedValue.isExpired()) {
					log.debug("Cache value expired for key '{}', removing", cacheKey);
					redisTemplate.delete(cacheKey);
					return null;
				}

				// 更新访问统计
				cachedValue.updateAccess();
				long remainingTtl = cachedValue.getRemainingTtl();

				// 同步更新到Redis，确保立即可见
				try {
					if (remainingTtl > 0) {
						redisTemplate.opsForValue().set(cacheKey, cachedValue, Duration.ofSeconds(remainingTtl));
					} else {
						redisTemplate.opsForValue().set(cacheKey, cachedValue);
					}
				} catch (Exception e) {
					log.warn("Failed to update cache statistics for key '{}': {}", cacheKey, e.getMessage());
				}

				log.debug("Cache hit for key '{}', visitTimes: {}", cacheKey, cachedValue.getVisitTimes());

				// 发布缓存命中事件
				long accessTime = System.currentTimeMillis() - startTime;
				publishCacheHitEvent(cacheKey, cachedValue.getValue(), accessTime);

				// CachedValue 已经处理了 null 值存储，直接返回
				return cachedValue.getValue();
			} else {
				// 兼容旧的缓存格式
				log.debug("Cache hit for key '{}' (legacy format)", cacheKey);
				return rawValue;
			}
		} catch (Exception e) {
			log.warn("Cache lookup failed for key '{}': {}", cacheKey, e.getMessage());
			return null;
		}
	}

	@Override
	public void put(Object key, @Nullable Object value) {
		putWithTtl(key, value, defaultTtl);
	}

	public void putWithTtl(Object key, @Nullable Object value, Duration ttl) {
		String cacheKey = createCacheKey(key);
		long startTime = System.currentTimeMillis();

		try {
			long ttlSeconds = (ttl != null && !ttl.isZero() && !ttl.isNegative()) ? ttl.getSeconds() : -1;

			// CachedValue 直接存储原始值（包括null）
			CachedValue cachedValue = CachedValue.of(
					value,
					value != null ? value.getClass() : Object.class,
					ttlSeconds
			);

			if (ttlSeconds > 0) {
				redisTemplate.opsForValue().set(cacheKey, cachedValue, ttl);
			} else {
				redisTemplate.opsForValue().set(cacheKey, cachedValue);
			}

			log.debug("Cache put for key '{}' with TTL: {} seconds, type: {}",
					cacheKey, ttlSeconds, cachedValue.getType().getSimpleName());

			// 发布缓存写入事件
			long executionTime = System.currentTimeMillis() - startTime;
			publishCachePutEvent(key, value, ttl, executionTime);

		} catch (Exception e) {
			log.warn("Cache put failed for key '{}': {}", cacheKey, e.getMessage());
		}
	}

	@Override
	public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
		String cacheKey = createCacheKey(key);

		try {
			long ttlSeconds = (defaultTtl != null && !defaultTtl.isZero() && !defaultTtl.isNegative()) ? defaultTtl.getSeconds() : -1;

			// CachedValue 直接存储原始值（包括null）
			CachedValue cachedValue = CachedValue.of(
					value,
					value != null ? value.getClass() : Object.class,
					ttlSeconds
			);

			Boolean result = ttlSeconds > 0 ?
					redisTemplate.opsForValue().setIfAbsent(cacheKey, cachedValue, defaultTtl) :
					redisTemplate.opsForValue().setIfAbsent(cacheKey, cachedValue);

			if (Boolean.TRUE.equals(result)) {
				log.debug("Cache putIfAbsent succeeded for key '{}'", cacheKey);
				return toValueWrapper(value);
			} else {
				Object existingRawValue = redisTemplate.opsForValue().get(cacheKey);
				if (existingRawValue instanceof CachedValue existingCachedValue) {
					if (!existingCachedValue.isExpired()) {
						existingCachedValue.updateAccess();
						// 同步更新访问统计到Redis
						try {
							long remainingTtl = existingCachedValue.getRemainingTtl();
							if (remainingTtl > 0) {
								redisTemplate.opsForValue().set(cacheKey, existingCachedValue, Duration.ofSeconds(remainingTtl));
							} else {
								redisTemplate.opsForValue().set(cacheKey, existingCachedValue);
							}
						} catch (Exception e) {
							log.warn("Failed to update cache statistics for key '{}': {}", cacheKey, e.getMessage());
						}
						log.debug("Cache putIfAbsent failed for key '{}', existing value found", cacheKey);
						return toValueWrapper(existingCachedValue.getValue());
					} else {
						// 已过期的情况下，重新设置
						putWithTtl(key, value, defaultTtl);
						return toValueWrapper(value);
					}
				} else if (existingRawValue != null) {
					// 兼容旧格式
					log.debug("Cache putIfAbsent failed for key '{}', existing legacy value found", cacheKey);
					return toValueWrapper(existingRawValue);
				} else {
					// 不存在，重新设置
					putWithTtl(key, value, defaultTtl);
					return toValueWrapper(value);
				}
			}
		} catch (Exception e) {
			log.warn("Cache putIfAbsent failed for key '{}': {}", cacheKey, e.getMessage());
			return null;
		}
	}

	@Override
	public void evict(@NonNull Object key) {
		String cacheKey = createCacheKey(key);
		try {
			Boolean result = redisTemplate.delete(cacheKey);
			log.debug("Cache evict for key '{}': {}", cacheKey, result ? "success" : "not found");
		} catch (Exception e) {
			log.warn("Cache evict failed for key '{}': {}", cacheKey, e.getMessage());
		}
	}

	@Override
	public void clear() {
		try {
			String pattern = name + ":*";
			redisTemplate.delete(redisTemplate.keys(pattern));
			log.debug("Cache clear for pattern '{}'", pattern);
		} catch (Exception e) {
			log.warn("Cache clear failed for cache '{}': {}", name, e.getMessage());
		}
	}

	@Override
	public <T> T get(Object key, Callable<T> valueLoader) {
		ValueWrapper result = get(key);
		if (result != null) {
			return (T) result.get();
		}

		try {
			T value = valueLoader.call();
			put(key, value);
			return value;
		} catch (Exception e) {
			throw new ValueRetrievalException(key, valueLoader, e);
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
	 * 发布缓存命中事件
	 */
	private void publishCacheHitEvent(Object cacheKey, Object value, long accessTime) {
		if (eventPublisher != null) {
			CacheHitEvent event = new CacheHitEvent(name, cacheKey, "RedisCache", value, accessTime);
			eventPublisher.publishEventAsync(event);
		}
	}

	/**
	 * 发布缓存未命中事件
	 */
	private void publishCacheMissEvent(Object cacheKey, String reason) {
		if (eventPublisher != null) {
			CacheMissEvent event = new CacheMissEvent(name, cacheKey, "RedisCache", reason);
			eventPublisher.publishEventAsync(event);
		}
	}

	/**
	 * 发布缓存写入事件
	 */
	private void publishCachePutEvent(Object cacheKey, Object value, Duration ttl, long executionTime) {
		if (eventPublisher != null) {
			CachePutEvent event = new CachePutEvent(name, cacheKey, "RedisCache", value, ttl, executionTime);
			eventPublisher.publishEventAsync(event);
		}
	}

	/**
	 * 缓存统计信息
	 */
		public record CacheStats(long visitTimes, long age, long remainingTtl, Class<?> valueType) {


		@Override
			public String toString() {
				return String.format("CacheStats{visitTimes=%d, age=%ds, remainingTtl=%ds, valueType=%s}",
						visitTimes, age, remainingTtl, valueType.getSimpleName());
			}
		}
}