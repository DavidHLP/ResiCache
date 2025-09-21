package com.david.spring.cache.redis.aspect.support;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.aspect.AbstractCacheAspect;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * 缓存操作执行器
 * 提供统一的缓存操作执行逻辑，避免切面代码重复
 */
@Slf4j
@Component
public class CacheOperationExecutor {

	private final CacheManager cacheManager;

	public CacheOperationExecutor(CacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}

	/**
	 * 执行 @Cacheable 逻辑
	 */
	public Object executeCacheable(ProceedingJoinPoint joinPoint, RedisCacheable annotation,
	                               AbstractCacheAspect.AspectExecutionContext context) throws Throwable {
		String[] cacheNames = KeyResolver.getCacheNames(annotation.value(), annotation.cacheNames());

		Object key = KeyResolver.resolveKey(context.targetBean(), context.method(),
				context.arguments(), annotation.keyGenerator());

		// 优先使用SpEL表达式
		if (annotation.key() != null && !annotation.key().isBlank()) {
			Object spelKey = KeyResolver.resolveKeySpEL(context.targetBean(), context.method(),
					context.arguments(), annotation.key());
			if (spelKey != null) {
				key = spelKey;
			}
		}

		// 简单的缓存查找逻辑
		for (String cacheName : cacheNames) {
			Cache cache = cacheManager.getCache(cacheName);
			if (cache != null) {
				Cache.ValueWrapper valueWrapper = cache.get(key);
				if (valueWrapper != null) {
					log.debug("Cache hit for key={} in cache={}", key, cacheName);
					return valueWrapper.get();
				}
			}
		}

		// 缓存未命中，执行原方法
		Object result = joinPoint.proceed();

		// 将结果放入缓存
		for (String cacheName : cacheNames) {
			Cache cache = cacheManager.getCache(cacheName);
			if (cache != null) {
				cache.put(key, result);
				log.debug("Cached result for key={} in cache={}", key, cacheName);
			}
		}

		return result;
	}

	/**
	 * 执行 @CacheEvict 逻辑
	 */
	public Object executeCacheEvict(ProceedingJoinPoint joinPoint, RedisCacheEvict annotation,
	                                AbstractCacheAspect.AspectExecutionContext context) throws Throwable {
		String[] cacheNames = KeyResolver.getCacheNames(annotation.value(), annotation.cacheNames());

		// 如果是方法执行前清除缓存
		if (annotation.beforeInvocation()) {
			performEviction(cacheNames, context, annotation);
		}

		Object result;
		try {
			result = joinPoint.proceed();
		} catch (Throwable ex) {
			// 如果方法执行失败且是方法执行前清除，不需要再清除
			if (annotation.beforeInvocation()) {
				throw ex;
			}
			// 如果是方法执行后清除，即使方法失败也可能需要清除（取决于业务逻辑）
			throw ex;
		}

		// 如果是方法执行后清除缓存
		if (!annotation.beforeInvocation()) {
			performEviction(cacheNames, context, annotation);
		}

		return result;
	}

	/**
	 * 执行缓存清除操作
	 */
	private void performEviction(String[] cacheNames, AbstractCacheAspect.AspectExecutionContext context,
	                            RedisCacheEvict annotation) {
		for (String cacheName : cacheNames) {
			Cache cache = cacheManager.getCache(cacheName);
			if (cache != null) {
				if (annotation.allEntries()) {
					cache.clear();
					log.debug("Cleared all entries in cache={}", cacheName);
				} else {
					Object key = null;
					// 优先使用SpEL表达式
					if (annotation.key() != null && !annotation.key().isBlank()) {
						key = KeyResolver.resolveKeySpEL(context.targetBean(), context.method(),
								context.arguments(), annotation.key());
					}
					// 回退到keyGenerator
					if (key == null) {
						key = KeyResolver.resolveKey(context.targetBean(), context.method(),
								context.arguments(), annotation.keyGenerator());
					}
					if (key != null) {
						cache.evict(key);
						log.debug("Evicted key={} from cache={}", key, cacheName);
					}
				}
			}
		}
	}
}