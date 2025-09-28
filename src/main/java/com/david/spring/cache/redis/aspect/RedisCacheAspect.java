package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.annotation.RedisCaching;
import com.david.spring.cache.redis.register.RedisCacheRegister;
import com.david.spring.cache.redis.register.interceptor.RedisCacheEvictOperation;
import com.david.spring.cache.redis.register.interceptor.RedisCacheableOperation;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.core.Ordered;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Aspect
public record RedisCacheAspect(RedisCacheRegister redisCacheRegister, KeyGenerator keyGenerator) implements Ordered {

	@Around("@annotation(com.david.spring.cache.redis.annotation.RedisCacheable)")
	public Object handleRedisCacheable(ProceedingJoinPoint joinPoint) throws Throwable {
		Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
		Object[] args = joinPoint.getArgs();
		Object target = joinPoint.getTarget();

		RedisCacheable redisCacheable = method.getAnnotation(RedisCacheable.class);
		String[] cacheNames = getCacheNames(redisCacheable);
		String cacheKey = generateKey(method, args, target);

		registerCacheOperation(redisCacheable, cacheNames, cacheKey, method);

		return joinPoint.proceed();
	}

	@Around("@annotation(com.david.spring.cache.redis.annotation.RedisCaching)")
	public Object handleRedisCaching(ProceedingJoinPoint joinPoint) throws Throwable {
		Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
		Object[] args = joinPoint.getArgs();
		Object target = joinPoint.getTarget();

		RedisCaching redisCaching = method.getAnnotation(RedisCaching.class);

		// 注册 RedisCacheable 操作
		for (RedisCacheable cacheable : redisCaching.redisCacheable()) {
			String[] cacheNames = getCacheNames(cacheable);
			String cacheKey = generateKey(method, args, target);
			registerCacheOperation(cacheable, cacheNames, cacheKey, method);
		}

		// 注册 RedisCacheEvict 操作
		for (RedisCacheEvict cacheEvict : redisCaching.redisCacheEvict()) {
			registerCacheEvictOperation(cacheEvict, method, args, target);
		}

		return joinPoint.proceed();
	}

	@Around("@annotation(com.david.spring.cache.redis.annotation.RedisCacheEvict)")
	public Object handleRedisCacheEvict(ProceedingJoinPoint joinPoint) throws Throwable {
		Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
		Object[] args = joinPoint.getArgs();
		Object target = joinPoint.getTarget();

		RedisCacheEvict redisCacheEvict = method.getAnnotation(RedisCacheEvict.class);
		registerCacheEvictOperation(redisCacheEvict, method, args, target);

		return joinPoint.proceed();
	}

	private String[] getCacheNames(RedisCacheable redisCacheable) {
		return getCacheNames(redisCacheable.cacheNames(), redisCacheable.value());
	}

	private String[] getCacheNames(RedisCacheEvict cacheEvict) {
		return getCacheNames(cacheEvict.cacheNames(), cacheEvict.value());
	}

	private String[] getCacheNames(String[] cacheNames, String[] values) {
		return cacheNames.length == 0 ? values : cacheNames;
	}

	private String generateKey(Method method, Object[] args, Object target) {
		// 使用 Spring Cache 默认的 KeyGenerator
		Object key = keyGenerator.generate(target, method, args);
		return String.valueOf(key);
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


	private void registerCacheEvictOperation(RedisCacheEvict cacheEvict, Method method, Object[] args, Object target) {
		try {
			String[] cacheNames = getCacheNames(cacheEvict);
			String key = generateKey(method, args, target);

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
		return Ordered.HIGHEST_PRECEDENCE;
	}
}