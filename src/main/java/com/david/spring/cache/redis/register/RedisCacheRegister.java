package com.david.spring.cache.redis.register;

import com.david.spring.cache.redis.register.interceptor.RedisCacheEvictOperation;
import com.david.spring.cache.redis.register.interceptor.RedisCacheableOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.CacheOperation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RedisCacheRegister {
	private final Map<Key, CacheOperation> cacheableOperations = new ConcurrentHashMap<>();

	public boolean registerCacheableOperation(RedisCacheableOperation cacheOperation) {
		for (String cacheName : cacheOperation.getCacheNames()) {
			Key key = Key.builder()
					.name(cacheName)
					.key(cacheOperation.getKey())
					.build();
			if (cacheableOperations.containsKey(key)) {
				log.warn("Cacheable operation for cache '{}' already registered", key.toString());
			}
			cacheableOperations.put(key, cacheOperation);
			log.info("Registered cacheable operation for cache '{}'", key.toString());
		}
		return true;
	}

	public boolean registerCacheEvictOperation(RedisCacheEvictOperation cacheOperation) {
		for (String cacheName : cacheOperation.getCacheNames()) {
			Key key = Key.builder()
					.name(cacheName)
					.key(cacheOperation.getKey())
					.build();
			if (cacheableOperations.containsKey(key)) {
				log.warn("CacheEvict operation for cache '{}' already registered", key.toString());
				return false;
			}
			cacheableOperations.put(key, cacheOperation);
			log.info("Registered CacheEvict operation for cache '{}'", key.toString());
		}
		return true;
	}

	public RedisCacheableOperation getCacheableOperation(String name, String key) {
		Key operationKey = Key.builder()
				.name(name)
				.key(key)
				.operationType(Key.OperationType.CACHE)
				.build();
		CacheOperation operation = cacheableOperations.get(operationKey);
		return operation instanceof RedisCacheableOperation ? (RedisCacheableOperation) operation : null;
	}

	public RedisCacheEvictOperation getCacheEvictOperation(String name, String key) {
		Key operationKey = Key.builder()
				.name(name)
				.key(key)
				.operationType(Key.OperationType.EVICT)
				.build();
		CacheOperation operation = cacheableOperations.get(operationKey);
		return operation instanceof RedisCacheEvictOperation ? (RedisCacheEvictOperation) operation : null;
	}
}
