package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.register.RedisCacheRegister;
import com.david.spring.cache.redis.register.interceptor.RedisCacheEvictOperation;
import com.david.spring.cache.redis.register.interceptor.RedisCacheableOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RedisCacheAspect implements Ordered {

	private final RedisCacheRegister redisCacheRegister;
	private final KeyGenerator keyGenerator;

	@Around("@annotation(com.david.spring.cache.redis.annotation.RedisCacheable)")
	public Object handleRedisCacheable(ProceedingJoinPoint joinPoint) throws Throwable {
		Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
		Object[] args = joinPoint.getArgs();
		Object target = joinPoint.getTarget();

		RedisCacheable redisCacheable = method.getAnnotation(RedisCacheable.class);
		String[] cacheNames = getCacheNames(redisCacheable);
		String key = generateKey(method, args, target);

		// 注册缓存操作
		registerCacheOperation(redisCacheable, cacheNames, key, method);

		// 直接执行原方法，让 Spring Cache 的标准拦截器处理缓存逻辑
		return joinPoint.proceed();
	}

	@Around("@annotation(com.david.spring.cache.redis.annotation.RedisCaching)")
	public Object handleRedisCaching(ProceedingJoinPoint joinPoint) throws Throwable {
		Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
		Object[] args = joinPoint.getArgs();
		Object target = joinPoint.getTarget();

		// 处理 @RedisCaching 注解中的所有 @RedisCacheable 注解
		com.david.spring.cache.redis.annotation.RedisCaching redisCaching =
				method.getAnnotation(com.david.spring.cache.redis.annotation.RedisCaching.class);

		for (RedisCacheable cacheable : redisCaching.redisCacheable()) {
			String[] cacheNames = getCacheNames(cacheable.cacheNames(), cacheable.value());
			String key = generateKey(method, args, target);
			registerCacheOperation(cacheable, cacheNames, key, method);
		}

		for (RedisCacheEvict cacheEvict : redisCaching.redisCacheEvict()) {
			registerCacheEvictOperation(cacheEvict, method, args, target);
		}

		// 直接执行原方法，让 Spring Cache 的标准拦截器处理缓存逻辑
		return joinPoint.proceed();
	}

	@Around("@annotation(com.david.spring.cache.redis.annotation.RedisCacheEvict)")
	public Object handleRedisCacheEvict(ProceedingJoinPoint joinPoint) throws Throwable {
		Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
		Object[] args = joinPoint.getArgs();
		Object target = joinPoint.getTarget();

		// 注册缓存驱逐操作
		registerCacheEvictOperation(method.getAnnotation(RedisCacheEvict.class), method, args, target);

		// 直接执行原方法，让 Spring Cache 的标准拦截器处理缓存逻辑
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
					.condition(redisCacheable.condition())
					.name(method.getName())
					.key(key)
					.cacheNames(cacheNames)
					.build();

			redisCacheRegister.registerCacheableOperation(operation);
			log.debug("Registered cacheable operation: {} with key: {} for caches: {}",
					method.getName(), key, String.join(",", cacheNames));
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
					.allEntries(cacheEvict.allEntries())
					.beforeInvocation(cacheEvict.beforeInvocation())
					.build();

			redisCacheRegister.registerCacheEvictOperation(operation);
			log.debug("Registered cache evict operation: {} with key: {} for caches: {}",
					method.getName(), key, String.join(",", cacheNames));
		} catch (Exception e) {
			log.error("Failed to register cache evict operation", e);
		}
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

}