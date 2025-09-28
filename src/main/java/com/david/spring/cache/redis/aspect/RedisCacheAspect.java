package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.annotation.RedisCaching;
import com.david.spring.cache.redis.register.RedisCacheRegister;
import com.david.spring.cache.redis.register.interceptor.RedisCacheEvictOperation;
import com.david.spring.cache.redis.register.interceptor.RedisCacheableOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.Ordered;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Aspect
@RequiredArgsConstructor
public class RedisCacheAspect implements Ordered {

	private final RedisCacheRegister redisCacheRegister;
	private final CacheManager cacheManager;
	private final ExpressionParser parser = new SpelExpressionParser();

	@Around("@annotation(redisCacheable)")
	public Object handleRedisCacheable(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable) throws Throwable {
		Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
		Object[] args = joinPoint.getArgs();

		String[] cacheNames = getCacheNames(redisCacheable);
		String cacheKey = generateKey(redisCacheable, method, args);

		for (String cacheName : cacheNames) {
			Cache cache = cacheManager.getCache(cacheName);
			if (cache != null) {
				Cache.ValueWrapper cachedValue = cache.get(cacheKey);
				if (cachedValue != null) {
					log.debug("Cache hit for key: {} in cache: {}", cacheKey, cacheName);
					return Objects.requireNonNull(cachedValue.get());
				}
			}
		}

		log.debug("Cache miss for key: {}, executing method", cacheKey);
		Object result = joinPoint.proceed();

		if (shouldCache(result, redisCacheable)) {
			for (String cacheName : cacheNames) {
				Cache cache = cacheManager.getCache(cacheName);
				if (cache != null) {
					cache.put(cacheKey, result);
					log.debug("Cached result for key: {} in cache: {}", cacheKey, cacheName);
				}
			}

			registerCacheOperation(redisCacheable, cacheNames, cacheKey, method);
		}

		return result;
	}

	@Around("@annotation(redisCaching)")
	public Object handleRedisCaching(ProceedingJoinPoint joinPoint, RedisCaching redisCaching) throws Throwable {
		Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
		Object[] args = joinPoint.getArgs();

		// 处理 RedisCacheEvict 操作（在方法执行前）
		for (RedisCacheEvict cacheEvict : redisCaching.cacheEvict()) {
			if (cacheEvict.beforeInvocation()) {
				handleCacheEvict(cacheEvict, method, args);
			}
		}

		Object result = null;
		boolean cacheHit = false;

		// 处理 RedisCacheable 操作
		for (RedisCacheable cacheable : redisCaching.cacheable()) {
			String[] cacheNames = getCacheNames(cacheable);
			String cacheKey = generateKey(cacheable, method, args);

			for (String cacheName : cacheNames) {
				Cache cache = cacheManager.getCache(cacheName);
				if (cache != null) {
					Cache.ValueWrapper cachedValue = cache.get(cacheKey);
					if (cachedValue != null) {
						log.debug("Cache hit for key: {} in cache: {}", cacheKey, cacheName);
						result = Objects.requireNonNull(cachedValue.get());
						cacheHit = true;
						break;
					}
				}
			}
			if (cacheHit) break;
		}

		// 如果没有缓存命中，执行方法
		if (!cacheHit) {
			log.debug("Cache miss, executing method");
			result = joinPoint.proceed();

			// 缓存结果
			for (RedisCacheable cacheable : redisCaching.cacheable()) {
				if (shouldCache(result, cacheable)) {
					String[] cacheNames = getCacheNames(cacheable);
					String cacheKey = generateKey(cacheable, method, args);

					for (String cacheName : cacheNames) {
						Cache cache = cacheManager.getCache(cacheName);
						if (cache != null) {
							cache.put(cacheKey, result);
							log.debug("Cached result for key: {} in cache: {}", cacheKey, cacheName);
						}
					}

					registerCacheOperation(cacheable, cacheNames, cacheKey, method);
				}
			}
		}

		// 处理 RedisCacheEvict 操作（在方法执行后）
		for (RedisCacheEvict cacheEvict : redisCaching.cacheEvict()) {
			if (!cacheEvict.beforeInvocation()) {
				handleCacheEvict(cacheEvict, method, args);
			}
		}

		return result;
	}

	@Around("@annotation(redisCacheEvict)")
	public Object handleRedisCacheEvict(ProceedingJoinPoint joinPoint, RedisCacheEvict redisCacheEvict) throws Throwable {
		Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
		Object[] args = joinPoint.getArgs();

		// 处理方法执行前的缓存清除
		if (redisCacheEvict.beforeInvocation()) {
			handleCacheEvict(redisCacheEvict, method, args);
		}

		Object result;
		try {
			result = joinPoint.proceed();
		} catch (Exception e) {
			// 如果beforeInvocation为true，即使方法执行失败也不再清除缓存
			if (!redisCacheEvict.beforeInvocation()) {
				throw e;
			}
			throw e;
		}

		// 处理方法执行后的缓存清除
		if (!redisCacheEvict.beforeInvocation()) {
			handleCacheEvict(redisCacheEvict, method, args);
		}

		return result;
	}

	private String[] getCacheNames(RedisCacheable redisCacheable) {
		String[] cacheNames = redisCacheable.cacheNames();
		if (cacheNames.length == 0) {
			cacheNames = redisCacheable.value();
		}
		return cacheNames;
	}

	private String generateKey(RedisCacheable redisCacheable, Method method, Object[] args) {
		String keyExpression = redisCacheable.key();
		if (keyExpression.isEmpty()) {
			return method.getName() + ":" + Arrays.toString(args);
		}

		try {
			EvaluationContext context = new StandardEvaluationContext();
			String[] paramNames = getParameterNames(method);
			for (int i = 0; i < args.length && i < paramNames.length; i++) {
				context.setVariable(paramNames[i], args[i]);
			}

			Expression expression = parser.parseExpression(keyExpression);
			return String.valueOf(expression.getValue(context));
		} catch (Exception e) {
			log.warn("Failed to evaluate key expression: {}, using default key", keyExpression, e);
			return method.getName() + ":" + Arrays.toString(args);
		}
	}

	private String[] getParameterNames(Method method) {
		return Arrays.stream(method.getParameters())
				.map(Parameter::getName)
				.toArray(String[]::new);
	}

	private boolean shouldCache(Object result, RedisCacheable redisCacheable) {

		String unless = redisCacheable.unless();
		if (!unless.isEmpty()) {
			try {
				EvaluationContext context = new StandardEvaluationContext();
				context.setVariable("result", result);
				Expression expression = parser.parseExpression(unless);
				Boolean shouldNotCache = expression.getValue(context, Boolean.class);
				return !Boolean.TRUE.equals(shouldNotCache);
			} catch (Exception e) {
				log.warn("Failed to evaluate unless expression: {}", unless, e);
			}
		}

		return true;
	}

	private void registerCacheOperation(RedisCacheable redisCacheable, String[] cacheNames, String key, Method method) {
		try {
			long ttl = redisCacheable.ttl();
			if (redisCacheable.randomTtl()) {
				float variance = redisCacheable.variance();
				long randomOffset = (long) (ttl * variance * (ThreadLocalRandom.current().nextFloat() - 0.5) * 2);
				ttl += randomOffset;
			}

			RedisCacheableOperation operation = RedisCacheableOperation.builder()
					.unless(redisCacheable.unless())
					.sync(redisCacheable.sync())
					.ttl(ttl)
					.type(redisCacheable.type())
					.useSecondLevelCache(redisCacheable.useSecondLevelCache())
					.distributedLock(redisCacheable.distributedLock())
					.internalLock(redisCacheable.internalLock())
					.cacheNullValues(redisCacheable.cacheNullValues())
					.useBloomFilter(redisCacheable.useBloomFilter())
					.randomTtl(redisCacheable.randomTtl())
					.variance(redisCacheable.variance())
					.enablePreRefresh(redisCacheable.enablePreRefresh())
					.preRefreshThreshold(redisCacheable.preRefreshThreshold())
					.name(method.getName())
					.key(key)
					.cacheNames(cacheNames)
					.build();

			redisCacheRegister.registerCacheableOperation(operation);
		} catch (Exception e) {
			log.error("Failed to register cache operation", e);
		}
	}

	private void handleCacheEvict(RedisCacheEvict cacheEvict, Method method, Object[] args) {
		try {
			if (!shouldEvictCache(cacheEvict, method, args)) {
				log.debug("Cache evict condition not met, skipping eviction");
				return;
			}

			String[] cacheNames = getCacheNames(cacheEvict);

			if (cacheEvict.allEntries()) {
				// 清除所有缓存条目
				for (String cacheName : cacheNames) {
					Cache cache = cacheManager.getCache(cacheName);
					if (cache != null) {
						cache.clear();
						log.debug("Cleared all entries in cache: {}", cacheName);
					}
				}
			} else {
				// 清除指定的缓存条目
				String cacheKey = generateKey(cacheEvict, method, args);
				for (String cacheName : cacheNames) {
					Cache cache = cacheManager.getCache(cacheName);
					if (cache != null) {
						cache.evict(cacheKey);
						log.debug("Evicted key: {} from cache: {}", cacheKey, cacheName);
					}
				}
			}

			registerCacheEvictOperation(cacheEvict, cacheNames, method, args);
		} catch (Exception e) {
			log.error("Failed to handle cache evict operation", e);
		}
	}

	private String[] getCacheNames(RedisCacheEvict cacheEvict) {
		String[] cacheNames = cacheEvict.cacheNames();
		if (cacheNames.length == 0) {
			cacheNames = cacheEvict.value();
		}
		return cacheNames;
	}

	private String generateKey(RedisCacheEvict cacheEvict, Method method, Object[] args) {
		String keyExpression = cacheEvict.key();
		if (keyExpression.isEmpty()) {
			return method.getName() + ":" + Arrays.toString(args);
		}

		try {
			EvaluationContext context = new StandardEvaluationContext();
			String[] paramNames = getParameterNames(method);
			for (int i = 0; i < args.length && i < paramNames.length; i++) {
				context.setVariable(paramNames[i], args[i]);
			}

			Expression expression = parser.parseExpression(keyExpression);
			return String.valueOf(expression.getValue(context));
		} catch (Exception e) {
			log.warn("Failed to evaluate key expression: {}, using default key", keyExpression, e);
			return method.getName() + ":" + Arrays.toString(args);
		}
	}

	private boolean shouldEvictCache(RedisCacheEvict cacheEvict, Method method, Object[] args) {
		String condition = cacheEvict.condition();
		if (condition.isEmpty()) {
			return true;
		}

		try {
			EvaluationContext context = new StandardEvaluationContext();
			String[] paramNames = getParameterNames(method);
			for (int i = 0; i < args.length && i < paramNames.length; i++) {
				context.setVariable(paramNames[i], args[i]);
			}

			Expression expression = parser.parseExpression(condition);
			Boolean shouldEvict = expression.getValue(context, Boolean.class);
			return Boolean.TRUE.equals(shouldEvict);
		} catch (Exception e) {
			log.warn("Failed to evaluate condition expression: {}", condition, e);
			return true;
		}
	}

	private void registerCacheEvictOperation(RedisCacheEvict cacheEvict, String[] cacheNames, Method method, Object[] args) {
		try {
			String key = generateKey(cacheEvict, method, args);

			RedisCacheEvictOperation operation = RedisCacheEvictOperation.builder()
					.sync(cacheEvict.sync())
					.key(key)
					.cacheNames(cacheNames)
					.condition(cacheEvict.condition())
					.build();

			redisCacheRegister.registerCacheEvictOperation(operation);
		} catch (Exception e) {
			log.error("Failed to register cache evict operation", e);
		}
	}

	@Override
	public int getOrder() {
		return 0;
	}
}