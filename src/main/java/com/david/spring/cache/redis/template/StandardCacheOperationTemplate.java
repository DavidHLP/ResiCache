package com.david.spring.cache.redis.template;

import com.david.spring.cache.redis.core.RedisCache;
import com.david.spring.cache.redis.manager.RedisCacheManager;
import org.springframework.cache.Cache;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * 标准缓存操作模板实现
 */
public class StandardCacheOperationTemplate extends CacheOperationTemplate {

	private final RedisCacheManager cacheManager;

	public StandardCacheOperationTemplate(RedisCacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}


	// 核心缓存操作方法实现
	@Override
	protected Object doLookup(String cacheName, Object key) {
		Cache cache = cacheManager.getCache(cacheName);
		if (cache == null) {
			return null;
		}

		if (cache instanceof RedisCache redisCache) {
			// 调用原始的直接查找方法
			return redisCache.doLookupDirect(key);
		} else {
			Cache.ValueWrapper wrapper = cache.get(key);
			return wrapper != null ? wrapper.get() : null;
		}
	}

	@Override
	protected void doPut(String cacheName, Object key, Object value, Duration ttl) {
		Cache cache = cacheManager.getCache(cacheName);
		if (cache != null) {
			if (cache instanceof RedisCache redisCache && ttl != null) {
				redisCache.doPutDirect(key, value, ttl);
			} else {
				cache.put(key, value);
			}
		}
	}

	@Override
	protected Cache.ValueWrapper doPutIfAbsent(String cacheName, Object key, Object value, Duration ttl) {
		Cache cache = cacheManager.getCache(cacheName);
		if (cache != null) {
			if (cache instanceof RedisCache redisCache) {
				return redisCache.doPutIfAbsentDirect(key, value);
			} else {
				return cache.putIfAbsent(key, value);
			}
		}
		return null;
	}

	@Override
	protected void doEvict(String cacheName, Object key) {
		Cache cache = cacheManager.getCache(cacheName);
		if (cache != null) {
			if (cache instanceof RedisCache redisCache) {
				redisCache.doEvictDirect(key);
			} else {
				cache.evict(key);
			}
		}
	}

	@Override
	protected void doClear(String cacheName) {
		Cache cache = cacheManager.getCache(cacheName);
		if (cache != null) {
			if (cache instanceof RedisCache redisCache) {
				redisCache.doClearDirect();
			} else {
				cache.clear();
			}
		}
	}

	@Override
	protected <T> T doGet(String cacheName, Object key, Callable<T> valueLoader) throws Exception {
		Cache cache = cacheManager.getCache(cacheName);
		if (cache != null) {
			if (cache instanceof RedisCache redisCache) {
				return redisCache.doGetDirect(key, valueLoader);
			} else {
				return cache.get(key, valueLoader);
			}
		}
		// 如果缓存不存在，直接调用valueLoader
		return valueLoader.call();
	}

}