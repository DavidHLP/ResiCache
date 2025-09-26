package com.david.spring.cache.redis.template;

import com.david.spring.cache.redis.core.RedisCache;
import com.david.spring.cache.redis.expression.CacheExpressionEvaluator;
import com.david.spring.cache.redis.manager.RedisCacheManager;
import com.david.spring.cache.redis.resolver.CacheOperationResolver;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 标准缓存操作模板实现
 */
public class StandardCacheOperationTemplate extends CacheOperationTemplate {

	// 缓存未命中的特殊标记
	private static final Object CACHE_MISS_MARKER = new Object();
	private final RedisCacheManager cacheManager;
	private final KeyGenerator keyGenerator;
	private final CacheExpressionEvaluator expressionEvaluator;

	public StandardCacheOperationTemplate(RedisCacheManager cacheManager,
	                                      @Qualifier("redisCacheKeyGenerator") KeyGenerator keyGenerator) {
		this.cacheManager = cacheManager;
		this.keyGenerator = keyGenerator;
		this.expressionEvaluator = new CacheExpressionEvaluator();
	}

	@Override
	protected Object generateCacheKey(CacheOperationResolver.CacheableOperation operation,
	                                  Method method, Object[] args, Object target, Class<?> targetClass) {
		if (operation.hasKey()) {
			Object key = expressionEvaluator.generateKey(operation.getKey(), method, args, target, targetClass, null);
			return key != null ? key : keyGenerator.generate(target, method, args);
		}
		return keyGenerator.generate(target, method, args);
	}

	@Override
	protected boolean shouldExecute(CacheOperationResolver.CacheableOperation operation,
	                                Method method, Object[] args, Object target, Class<?> targetClass) {
		return !operation.hasCondition() ||
				expressionEvaluator.evaluateCondition(operation.getCondition(), method, args, target, targetClass, null);
	}

	@Override
	protected Object getCachedValue(CacheOperationResolver.CacheableOperation operation,
	                                Object cacheKey, String cacheName) {
		Cache cache = cacheManager.getCache(cacheName);
		if (cache == null) {
			return CACHE_MISS_MARKER;
		}

		Cache.ValueWrapper wrapper = cache.get(cacheKey);
		if (wrapper != null) {
			// 缓存命中，返回实际值（可能是null）
			return wrapper.get();
		}

		// wrapper为null表示缓存未命中，返回特殊标记
		return CACHE_MISS_MARKER;
	}

	@Override
	protected boolean isCacheMissMarker(Object value) {
		return value == CACHE_MISS_MARKER;
	}

	@Override
	protected Object executeTargetMethod(ProceedingJoinPoint joinPoint,
	                                     CacheOperationResolver.CacheableOperation operation,
	                                     Object cacheKey, String cacheName) throws Throwable {
		// 执行目标方法
		Object result = joinPoint.proceed();

		// 缓存结果
		if (result != null || operation.isCacheNullValues()) {
			Cache cache = cacheManager.getCache(cacheName);
			if (cache != null) {
				Duration ttl = calculateTtl(operation);
				if (cache instanceof RedisCache redisCache && ttl != null) {
					redisCache.putWithTtl(cacheKey, result, ttl);
				} else {
					cache.put(cacheKey, result);
				}
			}
		}

		return result;
	}

	/**
	 * 计算TTL，支持随机TTL以避免缓存雪崩
	 */
	private Duration calculateTtl(CacheOperationResolver.CacheableOperation operation) {
		Duration baseTtl = operation.getTtl();

		if (operation.isRandomTtl() && baseTtl != null) {
			long baseSeconds = baseTtl.getSeconds();
			float variance = operation.getVariance();
			long randomOffset = (long) (baseSeconds * variance * (ThreadLocalRandom.current().nextFloat() - 0.5f) * 2);
			return Duration.ofSeconds(Math.max(1, baseSeconds + randomOffset));
		}

		return baseTtl;
	}
}