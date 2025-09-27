package com.david.spring.cache.redis.template;

import com.david.spring.cache.redis.core.CachedValue;
import com.david.spring.cache.redis.core.RedisCacheLogUtils;
import com.david.spring.cache.redis.manager.AbstractEventAwareCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;

import static com.david.spring.cache.redis.core.CacheConstants.CacheLayers;
import static com.david.spring.cache.redis.core.CacheConstants.Operations;

/**
 * 标准缓存操作模板实现
 */
@Slf4j
public class StandardCacheOperationTemplate extends CacheOperationTemplate {

	private final AbstractEventAwareCache eventSupport = new AbstractEventAwareCache() {};

	public StandardCacheOperationTemplate() {
	}


	// 核心缓存操作方法实现
	@Override
	protected Object doLookup(RedisTemplate<String, Object> redisTemplate, String cacheName, Object key, boolean allowNullValues) {
		String cacheKey = createCacheKey(cacheName, key);
		try {
			Object rawValue = redisTemplate.opsForValue().get(cacheKey);

			if (rawValue == null) {
				eventSupport.logCacheOperation(Operations.GET, cacheKey, "miss");
				log.debug("Cache miss: cache='{}', key='{}', reason='{}'", cacheName, cacheKey, "NOT_FOUND");
				return null;
			}

			// 检查是否是 CachedValue 类型
			if (rawValue instanceof CachedValue cachedValue) {
				// 检查是否过期
				if (cachedValue.isExpired()) {
					eventSupport.logCacheOperation(Operations.GET, cacheKey, "expired");
					redisTemplate.delete(cacheKey);
					log.debug("Cache miss: cache='{}', key='{}', reason='{}'", cacheName, cacheKey, "EXPIRED");
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
					RedisCacheLogUtils.logCacheStatisticsUpdateFailure(cacheKey, e);
				}

				// 发布缓存命中事件
				eventSupport.logCacheOperation(Operations.GET, cacheKey, "hit");
				log.debug("Cache hit: cache='{}', key='{}'", cacheName, cacheKey);

				// CachedValue 已经处理了 null 值存储
				Object value = cachedValue.getValue();

				// 如果值为null且allowNullValues为true，需要返回NullValue.INSTANCE
				// 这样AbstractValueAdaptingCache才能正确识别这是一个缓存的null值
				if (value == null && allowNullValues) {
					return org.springframework.cache.support.NullValue.INSTANCE;
				}

				return value;
			} else {
				// 兼容旧的缓存格式
				eventSupport.logCacheOperation(Operations.GET, cacheKey, "hit-legacy");
				return rawValue;
			}
		} catch (Exception e) {
			eventSupport.logCacheError(Operations.GET, cacheKey, e.getMessage());
			eventSupport.publishCacheErrorEvent(cacheName, cacheKey, CacheLayers.REDIS_CACHE, e, Operations.GET);
			return null;
		}
	}

	@Override
	protected void doPut(RedisTemplate<String, Object> redisTemplate, String cacheName, Object key, Object value, Duration ttl, boolean allowNullValues) {
		String cacheKey = createCacheKey(cacheName, key);

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

			// 发布缓存写入事件
			eventSupport.logCacheOperation(Operations.PUT, cacheKey, "success");
			log.debug("Cache put: cache='{}', key='{}', ttl='{}'", cacheName, key, ttl);

		} catch (Exception e) {
			eventSupport.logCacheError(Operations.PUT, cacheKey, e.getMessage());
			eventSupport.publishCacheErrorEvent(cacheName, cacheKey, CacheLayers.REDIS_CACHE, e, Operations.PUT);
		}
	}

	@Override
	protected Cache.ValueWrapper doPutIfAbsent(RedisTemplate<String, Object> redisTemplate, String cacheName, Object key, Object value, Duration ttl, boolean allowNullValues) {
		String cacheKey = createCacheKey(cacheName, key);

		try {
			long ttlSeconds = (ttl != null && !ttl.isZero() && !ttl.isNegative()) ? ttl.getSeconds() : -1;

			// CachedValue 直接存储原始值（包括null）
			CachedValue cachedValue = CachedValue.of(
					value,
					value != null ? value.getClass() : Object.class,
					ttlSeconds
			);

			Boolean result = ttlSeconds > 0 ?
					redisTemplate.opsForValue().setIfAbsent(cacheKey, cachedValue, ttl) :
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
							RedisCacheLogUtils.logCacheStatisticsUpdateFailure(cacheKey, e);
						}
						log.debug("Cache putIfAbsent failed for key '{}', existing value found", cacheKey);
						return toValueWrapper(existingCachedValue.getValue());
					} else {
						// 已过期的情况下，重新设置
						doPut(redisTemplate, cacheName, key, value, ttl, allowNullValues);
						return toValueWrapper(value);
					}
				} else if (existingRawValue != null) {
					// 兼容旧格式
					log.debug("Cache putIfAbsent failed for key '{}', existing legacy value found", cacheKey);
					return toValueWrapper(existingRawValue);
				} else {
					// 不存在，重新设置
					doPut(redisTemplate, cacheName, key, value, ttl, allowNullValues);
					return toValueWrapper(value);
				}
			}
		} catch (Exception e) {
			log.warn("Cache putIfAbsent failed for key '{}': {}", cacheKey, e.getMessage());
			return null;
		}
	}

	@Override
	protected void doEvict(RedisTemplate<String, Object> redisTemplate, String cacheName, Object key) {
		String cacheKey = createCacheKey(cacheName, key);

		// 发布驱逐开始事件
		eventSupport.publishOperationStartEvent(cacheName, key, CacheLayers.REDIS_CACHE, Operations.EVICT, Operations.EVICT);

		try {
			Boolean result = redisTemplate.delete(cacheKey);

			eventSupport.logCacheOperation(Operations.EVICT, cacheKey, result ? "success" : "not found");

			// 发布操作完成事件
			eventSupport.publishOperationEndEvent(cacheName, key, CacheLayers.REDIS_CACHE, Operations.EVICT, Operations.EVICT, 0, true);

		} catch (Exception e) {
			eventSupport.logCacheError(Operations.EVICT, cacheKey, e.getMessage());

			// 发布错误事件
			eventSupport.publishCacheErrorEvent(cacheName, key, CacheLayers.REDIS_CACHE, e, Operations.EVICT);

			// 发布操作完成事件（失败）
			eventSupport.publishOperationEndEvent(cacheName, key, CacheLayers.REDIS_CACHE, Operations.EVICT, Operations.EVICT, 0, false);
		}
	}

	@Override
	protected void doClear(RedisTemplate<String, Object> redisTemplate, String cacheName) {
		// 发布清空开始事件
		eventSupport.publishOperationStartEvent(cacheName, "*", CacheLayers.REDIS_CACHE, Operations.CLEAR, Operations.CLEAR);

		try {
			String pattern = cacheName + ":*";
			Set<String> keys = redisTemplate.keys(pattern);
			long deletedCount = 0;

			if (!keys.isEmpty()) {
				deletedCount = redisTemplate.delete(keys);
			}

			eventSupport.logCacheOperation(Operations.CLEAR, pattern, "deleted " + deletedCount + " keys");

			// 发布操作完成事件
			eventSupport.publishOperationEndEvent(cacheName, "*", CacheLayers.REDIS_CACHE, Operations.CLEAR, Operations.CLEAR, 0, true);

		} catch (Exception e) {
			eventSupport.logCacheError(Operations.CLEAR, cacheName, e.getMessage());

			// 发布错误事件
			eventSupport.publishCacheErrorEvent(cacheName, "*", CacheLayers.REDIS_CACHE, e, Operations.CLEAR);

			// 发布操作完成事件（失败）
			eventSupport.publishOperationEndEvent(cacheName, "*", CacheLayers.REDIS_CACHE, Operations.CLEAR, Operations.CLEAR, 0, false);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	protected <T> T doGet(RedisTemplate<String, Object> redisTemplate, String cacheName, Object key, Callable<T> valueLoader, boolean allowNullValues) throws Exception {
		Object result = doLookup(redisTemplate, cacheName, key, allowNullValues);
		if (result != null) {
			return (T) result;
		}

		try {
			T value = valueLoader.call();
			doPut(redisTemplate, cacheName, key, value, null, allowNullValues);
			return value;
		} catch (Exception e) {
			throw new org.springframework.cache.Cache.ValueRetrievalException(key, valueLoader, e);
		}
	}

	// 辅助方法
	private String createCacheKey(String cacheName, Object key) {
		return cacheName + ":" + key;
	}

	private Cache.ValueWrapper toValueWrapper(Object value) {
		return () -> value;
	}

}