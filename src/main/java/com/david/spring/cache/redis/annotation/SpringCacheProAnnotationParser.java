package com.david.spring.cache.redis.annotation;

import com.david.spring.cache.redis.interceptor.CacheProAbleOperation;
import com.david.spring.cache.redis.interceptor.CacheProEvictOperation;
import org.springframework.cache.annotation.CacheAnnotationParser;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

@SuppressWarnings("serial")
public class SpringCacheProAnnotationParser implements CacheAnnotationParser, Serializable {
	private static final Set<Class<? extends Annotation>> CACHE_OPERATION_ANNOTATIONS =
			Set.of(RedisCacheable.class, RedisCacheEvict.class, RedisCaching.class);


	@Override
	public boolean isCandidateClass(@NonNull Class<?> targetClass) {
		return AnnotationUtils.isCandidateClass(targetClass, CACHE_OPERATION_ANNOTATIONS);
	}

	@Override
	public Collection<CacheOperation> parseCacheAnnotations(@Nullable Class<?> type) {
		DefaultCacheConfig defaultConfig = new DefaultCacheConfig(type);
		return parseCacheAnnotations(defaultConfig, type);
	}

	@Override
	@Nullable
	public Collection<CacheOperation> parseCacheAnnotations(Method method) {
		DefaultCacheConfig defaultConfig = new DefaultCacheConfig(method.getDeclaringClass());
		return parseCacheAnnotations(defaultConfig, method);
	}

	@Nullable
	private Collection<CacheOperation> parseCacheAnnotations(DefaultCacheConfig cachingConfig, AnnotatedElement ae) {
		Collection<CacheOperation> ops = parseCacheAnnotations(cachingConfig, ae, false);
		if (ops != null && ops.size() > 1) {
			// More than one operation found -> local declarations override interface-declared ones...
			Collection<CacheOperation> localOps = parseCacheAnnotations(cachingConfig, ae, true);
			if (localOps != null) {
				return localOps;
			}
		}
		return ops;
	}

	@Nullable
	private Collection<CacheOperation> parseCacheAnnotations(
			DefaultCacheConfig cachingConfig, AnnotatedElement ae, boolean localOnly) {

		Collection<? extends Annotation> annotations = (localOnly ?
				AnnotatedElementUtils.getAllMergedAnnotations(ae, CACHE_OPERATION_ANNOTATIONS) :
				AnnotatedElementUtils.findAllMergedAnnotations(ae, CACHE_OPERATION_ANNOTATIONS));
		if (annotations.isEmpty()) {
			return null;
		}

		Collection<CacheOperation> ops = new ArrayList<>(1);
		annotations.stream().filter(RedisCacheable.class::isInstance).map(RedisCacheable.class::cast).forEach(
				cacheable -> ops.add(parseCacheableAnnotation(ae, cachingConfig, cacheable)));
		annotations.stream().filter(RedisCacheEvict.class::isInstance).map(RedisCacheEvict.class::cast).forEach(
				cacheEvict -> ops.add(parseEvictAnnotation(ae, cachingConfig, cacheEvict)));
		annotations.stream().filter(RedisCaching.class::isInstance).map(RedisCaching.class::cast).forEach(
				caching -> parseCachingAnnotation(ae, cachingConfig, caching, ops));
		return ops;
	}

	private CacheProAbleOperation parseCacheableAnnotation(
			AnnotatedElement ae, DefaultCacheConfig defaultConfig, RedisCacheable cacheable) {

		CacheProAbleOperation.Builder baseBuilder = new CacheProAbleOperation.Builder();
		baseBuilder.setName(ae.toString());
		baseBuilder.setCacheNames(cacheable.cacheNames());
		baseBuilder.setCondition(cacheable.condition());
		baseBuilder.setUnless(cacheable.unless());
		baseBuilder.setKey(cacheable.key());
		baseBuilder.setKeyGenerator(cacheable.keyGenerator());
		baseBuilder.setCacheManager(cacheable.cacheManager());
		baseBuilder.setCacheResolver(cacheable.cacheResolver());
		baseBuilder.setSync(cacheable.sync());
		baseBuilder.setTtl(cacheable.ttl());
		baseBuilder.setType(cacheable.type());
		baseBuilder.setUseSecondLevelCache(cacheable.useSecondLevelCache());
		baseBuilder.setDistributedLock(cacheable.distributedLock());
		baseBuilder.setInternalLock(cacheable.internalLock());
		baseBuilder.setCacheNullValues(cacheable.cacheNullValues());
		baseBuilder.setUseBloomFilter(cacheable.useBloomFilter());
		baseBuilder.setRandomTtl(cacheable.randomTtl());
		baseBuilder.setVariance(cacheable.variance());
		baseBuilder.setEnablePreRefresh(cacheable.enablePreRefresh());
		baseBuilder.setPreRefreshThreshold(cacheable.preRefreshThreshold());

		defaultConfig.applyDefault(baseBuilder);
		CacheProAbleOperation baseOp = baseBuilder.build();
		validateCacheOperation(ae, baseOp);

		return baseOp;
	}

	private CacheProEvictOperation parseEvictAnnotation(
			AnnotatedElement ae, DefaultCacheConfig defaultConfig, RedisCacheEvict cacheEvict) {

		CacheProEvictOperation.Builder baseBuilder = new CacheProEvictOperation.Builder();
		baseBuilder.setName(ae.toString());
		baseBuilder.setCacheNames(cacheEvict.cacheNames());
		baseBuilder.setCondition(cacheEvict.condition());
		baseBuilder.setKey(cacheEvict.key());
		baseBuilder.setKeyGenerator(cacheEvict.keyGenerator());
		baseBuilder.setCacheManager(cacheEvict.cacheManager());
		baseBuilder.setCacheResolver(cacheEvict.cacheResolver());
		baseBuilder.setCacheWide(cacheEvict.allEntries());
		baseBuilder.setBeforeInvocation(cacheEvict.beforeInvocation());
		baseBuilder.setSync(cacheEvict.sync());

		defaultConfig.applyDefault(baseBuilder);
		CacheProEvictOperation baseOp = baseBuilder.build();
		validateCacheOperation(ae, baseOp);

		return baseOp;
	}

	private void parseCachingAnnotation(
			AnnotatedElement ae, DefaultCacheConfig defaultConfig, RedisCaching caching, Collection<CacheOperation> ops) {

		RedisCacheable[] cacheables = caching.cacheable();
		for (RedisCacheable cacheable : cacheables) {
			ops.add(parseCacheableAnnotation(ae, defaultConfig, cacheable));
		}
		RedisCacheEvict[] cacheEvicts = caching.cacheEvict();
		for (RedisCacheEvict cacheEvict : cacheEvicts) {
			ops.add(parseEvictAnnotation(ae, defaultConfig, cacheEvict));
		}
	}


	/**
	 * Validates the specified {@link CacheOperation}.
	 * <p>Throws an {@link IllegalStateException} if the state of the operation is
	 * invalid. As there might be multiple sources for default values, this ensures
	 * that the operation is in a proper state before being returned.
	 *
	 * @param ae        the annotated element of the cache operation
	 * @param operation the {@link CacheOperation} to validate
	 */
	private void validateCacheOperation(AnnotatedElement ae, CacheOperation operation) {
		if (StringUtils.hasText(operation.getKey()) && StringUtils.hasText(operation.getKeyGenerator())) {
			throw new IllegalStateException("Invalid cache annotation configuration on '" +
					ae.toString() + "'. Both 'key' and 'keyGenerator' attributes have been set. " +
					"These attributes are mutually exclusive: either set the SpEL expression used to" +
					"compute the key at runtime or set the name of the KeyGenerator bean to use.");
		}
		if (StringUtils.hasText(operation.getCacheManager()) && StringUtils.hasText(operation.getCacheResolver())) {
			throw new IllegalStateException("Invalid cache annotation configuration on '" +
					ae.toString() + "'. Both 'cacheManager' and 'cacheResolver' attributes have been set. " +
					"These attributes are mutually exclusive: the cache manager is used to configure a" +
					"default cache resolver if none is set. If a cache resolver is set, the cache manager" +
					"won't be used.");
		}
	}

	@Override
	public boolean equals(@Nullable Object other) {
		return (other instanceof SpringCacheProAnnotationParser);
	}

	@Override
	public int hashCode() {
		return SpringCacheProAnnotationParser.class.hashCode();
	}


	/**
	 * Provides default settings for a given set of cache operations.
	 */
	private static class DefaultCacheConfig {

		private final Class<?> target;

		@Nullable
		private String[] cacheNames;

		@Nullable
		private String keyGenerator;

		@Nullable
		private String cacheManager;

		@Nullable
		private String cacheResolver;

		private boolean initialized = false;

		public DefaultCacheConfig(Class<?> target) {
			this.target = target;
		}

		/**
		 * Apply the defaults to the specified {@link CacheOperation.Builder}.
		 *
		 * @param builder the operation builder to update
		 */
		public void applyDefault(CacheOperation.Builder builder) {
			if (!this.initialized) {
				CacheConfig annotation = AnnotatedElementUtils.findMergedAnnotation(this.target, CacheConfig.class);
				if (annotation != null) {
					this.cacheNames = annotation.cacheNames();
					this.keyGenerator = annotation.keyGenerator();
					this.cacheManager = annotation.cacheManager();
					this.cacheResolver = annotation.cacheResolver();
				}
				this.initialized = true;
			}

			if (builder.getCacheNames().isEmpty() && this.cacheNames != null) {
				builder.setCacheNames(this.cacheNames);
			}
			if (!StringUtils.hasText(builder.getKey()) && !StringUtils.hasText(builder.getKeyGenerator()) &&
					StringUtils.hasText(this.keyGenerator)) {
				builder.setKeyGenerator(this.keyGenerator);
			}

			if (StringUtils.hasText(builder.getCacheManager()) || StringUtils.hasText(builder.getCacheResolver())) {
				// One of these is set so we should not inherit anything
			} else if (StringUtils.hasText(this.cacheResolver)) {
				builder.setCacheResolver(this.cacheResolver);
			} else if (StringUtils.hasText(this.cacheManager)) {
				builder.setCacheManager(this.cacheManager);
			}
		}
	}

}