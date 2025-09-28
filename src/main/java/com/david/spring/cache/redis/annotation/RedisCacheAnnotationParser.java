package com.david.spring.cache.redis.annotation;

import com.david.spring.cache.redis.register.interceptor.RedisCacheEvictOperation;
import com.david.spring.cache.redis.register.interceptor.RedisCacheableOperation;
import org.springframework.cache.annotation.CacheAnnotationParser;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class RedisCacheAnnotationParser implements CacheAnnotationParser, Serializable {

	@Override
	public Collection<CacheOperation> parseCacheAnnotations(Class<?> type) {
		return parseCacheAnnotations((AnnotatedElement) type);
	}

	@Override
	public Collection<CacheOperation> parseCacheAnnotations(java.lang.reflect.Method method) {
		return parseCacheAnnotations((AnnotatedElement) method);
	}

	private Collection<CacheOperation> parseCacheAnnotations(AnnotatedElement ae) {
		List<CacheOperation> ops = new ArrayList<>();

		RedisCacheable cacheable = ae.getAnnotation(RedisCacheable.class);
		if (cacheable != null) {
			ops.add(parseRedisCacheable(cacheable, ae));
		}

		RedisCacheEvict cacheEvict = ae.getAnnotation(RedisCacheEvict.class);
		if (cacheEvict != null) {
			ops.add(parseRedisCacheEvict(cacheEvict, ae));
		}

		RedisCaching caching = ae.getAnnotation(RedisCaching.class);
		if (caching != null) {
			for (RedisCacheable c : caching.redisCacheable()) {
				ops.add(parseRedisCacheable(c, ae));
			}
			for (RedisCacheEvict e : caching.redisCacheEvict()) {
				ops.add(parseRedisCacheEvict(e, ae));
			}
		}

		return ops.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(ops);
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
				.sync(ann.sync());

		return builder.build();
	}
}
