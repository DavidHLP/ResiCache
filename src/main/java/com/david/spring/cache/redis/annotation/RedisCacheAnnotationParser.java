package com.david.spring.cache.redis.annotation;

import com.david.spring.cache.redis.register.interceptor.RedisCacheEvictOperation;
import com.david.spring.cache.redis.register.interceptor.RedisCacheableOperation;
import org.springframework.cache.annotation.SpringCacheAnnotationParser;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class RedisCacheAnnotationParser extends SpringCacheAnnotationParser implements Serializable {

	@Override
	@Nullable
	public Collection<CacheOperation> parseCacheAnnotations(Class<?> type) {
		return parseCacheAnnotations((AnnotatedElement) type);
	}

	@Override
	@Nullable
	public Collection<CacheOperation> parseCacheAnnotations(java.lang.reflect.Method method) {
		return parseCacheAnnotations((AnnotatedElement) method);
	}

	@Nullable
	private Collection<CacheOperation> parseCacheAnnotations(AnnotatedElement ae) {
		List<CacheOperation> ops = new ArrayList<>();

		RedisCacheable cacheable = ae.getAnnotation(RedisCacheable.class);
		if (cacheable != null) {
			RedisCacheableOperation operation = parseRedisCacheable(cacheable, ae);
			validateCacheOperation(ae, operation);
			ops.add(operation);
		}

		RedisCacheEvict cacheEvict = ae.getAnnotation(RedisCacheEvict.class);
		if (cacheEvict != null) {
			RedisCacheEvictOperation operation = parseRedisCacheEvict(cacheEvict, ae);
			validateCacheOperation(ae, operation);
			ops.add(operation);
		}

		RedisCaching caching = ae.getAnnotation(RedisCaching.class);
		if (caching != null) {
			for (RedisCacheable c : caching.redisCacheable()) {
				RedisCacheableOperation operation = parseRedisCacheable(c, ae);
				validateCacheOperation(ae, operation);
				ops.add(operation);
			}
			for (RedisCacheEvict e : caching.redisCacheEvict()) {
				RedisCacheEvictOperation operation = parseRedisCacheEvict(e, ae);
				validateCacheOperation(ae, operation);
				ops.add(operation);
			}
		}

		return ops.isEmpty() ? null : Collections.unmodifiableList(ops);
	}

	private RedisCacheableOperation parseRedisCacheable(RedisCacheable ann, AnnotatedElement ae) {
		RedisCacheableOperation.Builder builder = new RedisCacheableOperation.Builder()
				.name(ae.toString())
				.cacheNames(ann.value().length > 0 ? ann.value() : ann.cacheNames())
				.key(StringUtils.hasText(ann.key()) ? ann.key() : null)
				.keyGenerator(StringUtils.hasText(ann.keyGenerator()) ? ann.keyGenerator() : null)
				.cacheManager(StringUtils.hasText(ann.cacheManager()) ? ann.cacheManager() : null)
				.cacheResolver(StringUtils.hasText(ann.cacheResolver()) ? ann.cacheResolver() : null)
				.condition(StringUtils.hasText(ann.condition()) ? ann.condition() : null)
				.unless(StringUtils.hasText(ann.unless()) ? ann.unless() : null)
				.sync(ann.sync())
				.ttl(ann.ttl())
				.type(ann.type())
				.useSecondLevelCache(ann.useSecondLevelCache())
				.distributedLock(ann.distributedLock())
				.internalLock(ann.internalLock())
				.cacheNullValues(ann.cacheNullValues())
				.useBloomFilter(ann.useBloomFilter())
				.randomTtl(ann.randomTtl())
				.variance(ann.variance())
				.enablePreRefresh(ann.enablePreRefresh())
				.preRefreshThreshold(ann.preRefreshThreshold());

		return builder.build();
	}

	private RedisCacheEvictOperation parseRedisCacheEvict(RedisCacheEvict ann, AnnotatedElement ae) {
		RedisCacheEvictOperation.Builder builder = new RedisCacheEvictOperation.Builder()
				.name(ae.toString())
				.cacheNames(ann.value().length > 0 ? ann.value() : ann.cacheNames())
				.key(StringUtils.hasText(ann.key()) ? ann.key() : null)
				.keyGenerator(StringUtils.hasText(ann.keyGenerator()) ? ann.keyGenerator() : null)
				.cacheManager(StringUtils.hasText(ann.cacheManager()) ? ann.cacheManager() : null)
				.cacheResolver(StringUtils.hasText(ann.cacheResolver()) ? ann.cacheResolver() : null)
				.condition(StringUtils.hasText(ann.condition()) ? ann.condition() : null)
				.sync(ann.sync())
				.allEntries(ann.allEntries())
				.beforeInvocation(ann.beforeInvocation());

		return builder.build();
	}

	private void validateCacheOperation(AnnotatedElement ae, CacheOperation operation) {
		if (StringUtils.hasText(operation.getKey()) && StringUtils.hasText(operation.getKeyGenerator())) {
			throw new IllegalStateException("Invalid cache annotation configuration on '" +
					ae.toString() + "'. Both 'key' and 'keyGenerator' attributes have been set. " +
					"These attributes are mutually exclusive: either set the SpEL expression used to " +
					"compute the key at runtime or set the name of the KeyGenerator bean to use.");
		}
		if (StringUtils.hasText(operation.getCacheManager()) && StringUtils.hasText(operation.getCacheResolver())) {
			throw new IllegalStateException("Invalid cache annotation configuration on '" +
					ae.toString() + "'. Both 'cacheManager' and 'cacheResolver' attributes have been set. " +
					"These attributes are mutually exclusive: the cache manager is used to configure a " +
					"default cache resolver if none is set. If a cache resolver is set, the cache manager " +
					"won't be used.");
		}

		if (operation.getCacheNames().isEmpty()) {
			throw new IllegalStateException("Invalid cache annotation configuration on '" +
					ae.toString() + "'. At least one cache name must be specified.");
		}
	}
}
