package com.david.spring.cache.redis.cache.support;

import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.registry.RegistryFactory;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public record CacheRegistryService(RegistryFactory registryFactory) {

	public CachedInvocation findInvocation(String cacheName, Object key) {
		try {
			return registryFactory.getCacheInvocationRegistry().get(cacheName, key).orElse(null);
		} catch (Exception e) {
			log.debug("Failed to find invocation for cache: {}, key: {}", cacheName, key, e);
			return null;
		}
	}

	public void cleanupRegistries(String cacheName, Object key) {
		try {
			registryFactory.getCacheInvocationRegistry().remove(cacheName, key);
		} catch (Exception e) {
			log.debug("Failed to remove key from cache registry: cache={}, key={}", cacheName, key, e);
		}

		try {
			registryFactory.getEvictInvocationRegistry().remove(cacheName, key);
		} catch (Exception e) {
			log.debug("Failed to remove key from evict registry: cache={}, key={}", cacheName, key, e);
		}
	}

	public void cleanupAllRegistries(String cacheName) {
		try {
			registryFactory.getCacheInvocationRegistry().removeAll(cacheName);
		} catch (Exception e) {
			log.debug("Failed to clear cache registry: cache={}", cacheName, e);
		}

		try {
			registryFactory.getEvictInvocationRegistry().removeAll(cacheName);
		} catch (Exception e) {
			log.debug("Failed to clear evict registry: cache={}", cacheName, e);
		}
	}
}