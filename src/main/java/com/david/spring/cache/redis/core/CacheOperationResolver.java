package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.annotation.RedisCaching;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class CacheOperationResolver {

	public List<CacheableOperation> resolveCacheableOperations(Method method, Class<?> targetClass) {
		List<CacheableOperation> operations = new ArrayList<>();

		RedisCacheable cacheable = AnnotatedElementUtils.findMergedAnnotation(method, RedisCacheable.class);
		if (cacheable != null) {
			operations.add(createCacheableOperation(cacheable, method, targetClass));
		}

		RedisCaching caching = AnnotatedElementUtils.findMergedAnnotation(method, RedisCaching.class);
		if (caching != null) {
			for (RedisCacheable c : caching.cacheable()) {
				operations.add(createCacheableOperation(c, method, targetClass));
			}
		}

		return operations;
	}

	public List<EvictOperation> resolveEvictOperations(Method method, Class<?> targetClass) {
		List<EvictOperation> operations = new ArrayList<>();

		RedisCacheEvict evict = AnnotatedElementUtils.findMergedAnnotation(method, RedisCacheEvict.class);
		if (evict != null) {
			operations.add(createEvictOperation(evict, method, targetClass));
		}

		RedisCaching caching = AnnotatedElementUtils.findMergedAnnotation(method, RedisCaching.class);
		if (caching != null) {
			for (RedisCacheEvict e : caching.cacheEvict()) {
				operations.add(createEvictOperation(e, method, targetClass));
			}
		}

		return operations;
	}

	private CacheableOperation createCacheableOperation(RedisCacheable cacheable, Method method, Class<?> targetClass) {
		CacheableOperation operation = new CacheableOperation();
		operation.setCacheNames(resolveCacheNames(cacheable.value(), cacheable.cacheNames()));
		operation.setKey(cacheable.key());
		operation.setKeyGenerator(cacheable.keyGenerator());
		operation.setCondition(cacheable.condition());
		operation.setUnless(cacheable.unless());
		operation.setSync(cacheable.sync());
		operation.setTtl(Duration.ofSeconds(cacheable.ttl()));
		operation.setType(cacheable.type());
		operation.setUseSecondLevelCache(cacheable.useSecondLevelCache());
		operation.setDistributedLock(cacheable.distributedLock());
		operation.setInternalLock(cacheable.internalLock());
		operation.setCacheNullValues(cacheable.cacheNullValues());
		operation.setUseBloomFilter(cacheable.useBloomFilter());
		operation.setRandomTtl(cacheable.randomTtl());
		operation.setVariance(cacheable.variance());
		operation.setEnablePreRefresh(cacheable.enablePreRefresh());
		operation.setPreRefreshThreshold(cacheable.preRefreshThreshold());
		operation.setMethod(method);
		operation.setTargetClass(targetClass);

		log.debug("Created CacheableOperation for method {}.{}: cacheNames={}, ttl={}",
				targetClass.getSimpleName(), method.getName(), operation.getCacheNames(), operation.getTtl());

		return operation;
	}

	private EvictOperation createEvictOperation(RedisCacheEvict evict, Method method, Class<?> targetClass) {
		EvictOperation operation = new EvictOperation();
		operation.setCacheNames(resolveCacheNames(evict.value(), evict.cacheNames()));
		operation.setKey(evict.key());
		operation.setKeyGenerator(evict.keyGenerator());
		operation.setCondition(evict.condition());
		operation.setAllEntries(evict.allEntries());
		operation.setBeforeInvocation(evict.beforeInvocation());
		operation.setSync(evict.sync());
		operation.setMethod(method);
		operation.setTargetClass(targetClass);

		log.debug("Created EvictOperation for method {}.{}: cacheNames={}, allEntries={}",
				targetClass.getSimpleName(), method.getName(), operation.getCacheNames(), operation.isAllEntries());

		return operation;
	}

	private String[] resolveCacheNames(String[] value, String[] cacheNames) {
		if (value.length > 0) {
			return value;
		}
		if (cacheNames.length > 0) {
			return cacheNames;
		}
		throw new IllegalStateException("Cache names must be specified");
	}

	@Data
	public static class CacheableOperation {
		private String[] cacheNames;
		private String key;
		private String keyGenerator;
		private String condition;
		private String unless;
		private boolean sync;
		private Duration ttl;
		private Class<?> type;
		private boolean useSecondLevelCache;
		private boolean distributedLock;
		private boolean internalLock;
		private boolean cacheNullValues;
		private boolean useBloomFilter;
		private boolean randomTtl;
		private float variance;
		private boolean enablePreRefresh;
		private double preRefreshThreshold;
		private Method method;
		private Class<?> targetClass;

		public boolean hasKey() {
			return StringUtils.hasText(key);
		}

		public boolean hasKeyGenerator() {
			return StringUtils.hasText(keyGenerator);
		}

		public boolean hasCondition() {
			return StringUtils.hasText(condition);
		}

		public boolean hasUnless() {
			return StringUtils.hasText(unless);
		}
	}

	@Data
	public static class EvictOperation {
		private String[] cacheNames;
		private String key;
		private String keyGenerator;
		private String condition;
		private boolean allEntries;
		private boolean beforeInvocation;
		private boolean sync;
		private Method method;
		private Class<?> targetClass;

		public boolean hasKey() {
			return StringUtils.hasText(key);
		}

		public boolean hasKeyGenerator() {
			return StringUtils.hasText(keyGenerator);
		}

		public boolean hasCondition() {
			return StringUtils.hasText(condition);
		}
	}
}