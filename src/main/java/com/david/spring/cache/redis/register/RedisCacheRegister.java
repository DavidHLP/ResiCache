package com.david.spring.cache.redis.register;

import com.david.spring.cache.redis.register.interceptor.RedisCacheEvictOperation;
import com.david.spring.cache.redis.register.interceptor.RedisCacheableOperation;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RedisCacheRegister {
	private final Map<Key, CacheOperation> cacheableOperations = new ConcurrentHashMap<>();

	public boolean registerCacheableOperation(RedisCacheableOperation cacheOperation) {
		for (String cacheName : cacheOperation.getCacheNames()) {
			Key key = Key.builder()
					.name(cacheName)
					.key(cacheOperation.getKey())
					.operationType(Key.OperationType.CACHE)
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
					.operationType(Key.OperationType.EVICT)
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
		// 先尝试直接匹配
		Key operationKey = Key.builder()
				.name(name)
				.key(key)
				.operationType(Key.OperationType.CACHE)
				.build();
		CacheOperation operation = cacheableOperations.get(operationKey);
		if (operation instanceof RedisCacheableOperation) {
			return (RedisCacheableOperation) operation;
		}

		// 如果直接匹配失败，尝试通过cacheName查找
		return cacheableOperations.values().stream()
				.filter(op -> op instanceof RedisCacheableOperation)
				.map(op -> (RedisCacheableOperation) op)
				.filter(op -> op.getCacheNames().contains(name))
				.findFirst()
				.orElse(null);
	}

	public RedisCacheEvictOperation getCacheEvictOperation(String name, String key) {
		// 先尝试直接匹配
		Key operationKey = Key.builder()
				.name(name)
				.key(key)
				.operationType(Key.OperationType.EVICT)
				.build();
		CacheOperation operation = cacheableOperations.get(operationKey);
		if (operation instanceof RedisCacheEvictOperation) {
			return (RedisCacheEvictOperation) operation;
		}

		// 如果直接匹配失败，尝试通过cacheName查找
		return cacheableOperations.values().stream()
				.filter(op -> op instanceof RedisCacheEvictOperation)
				.map(op -> (RedisCacheEvictOperation) op)
				.filter(op -> op.getCacheNames().contains(name))
				.findFirst()
				.orElse(null);
	}
}

@Builder
record Key(String name, String key, OperationType operationType) {

	@Override
	@NonNull
	public String toString() {
		return "Key{" +
				"name='" + name + '\'' +
				", key='" + key + '\'' +
				", operationType=" + operationType +
				'}';
	}

	public enum OperationType {
		EVICT,
		CACHE
	}
}
