package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.aspect.support.KeyResolver;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

import static cn.hutool.core.text.CharSequenceUtil.nullToEmpty;

@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RedisCacheableAspect {

	private final CacheInvocationRegistry registry;


	public RedisCacheableAspect(CacheInvocationRegistry registry) {
		this.registry = registry;
	}

	@SneakyThrows
	@Around("@annotation(redisCacheable)")
	public Object around(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable) {
		try {
			registerInvocation(joinPoint, redisCacheable);
		} catch (Exception e) {
			log.warn("Failed to register cached invocation: {}", e.getMessage());
		}
		return joinPoint.proceed();
	}

	private void registerInvocation(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable)
			throws NoSuchMethodException {

		Method method = getSpecificMethod(joinPoint);
		Object targetBean = joinPoint.getTarget();
		Object[] arguments = joinPoint.getArgs();
		String[] cacheNames =
				KeyResolver.getCacheNames(redisCacheable.value(), redisCacheable.cacheNames());

		Object key = resolveCacheKey(targetBean, method, arguments, redisCacheable);

		CachedInvocation cachedInvocation =
				CachedInvocation.builder()
						.arguments(arguments)
						.targetBean(targetBean)
						.targetMethod(method)
						.cachedInvocationContext(
								CachedInvocationContext.builder()
										.value(redisCacheable.value())
										.cacheNames(redisCacheable.cacheNames())
										.key(nullToEmpty(redisCacheable.key()))
										.keyGenerator(redisCacheable.keyGenerator())
										.cacheManager(redisCacheable.cacheManager())
										.cacheResolver(redisCacheable.cacheResolver())
										.condition(nullToEmpty(redisCacheable.condition()))
										.unless(nullToEmpty(redisCacheable.unless()))
										.sync(redisCacheable.sync())
										.ttl(redisCacheable.ttl())
										.type(redisCacheable.type())
										.useBloomFilter(redisCacheable.useBloomFilter())
										.randomTtl(redisCacheable.randomTtl())
										.variance(redisCacheable.variance())
										.cacheNullValues(redisCacheable.cacheNullValues())
										.distributedLock(redisCacheable.distributedLock())
										.distributedLockName(nullToEmpty(redisCacheable.distributedLockName()))
										.internalLock(redisCacheable.internalLock())
										.useSecondLevelCache(redisCacheable.useSecondLevelCache())
										.fetchStrategy(parseFetchStrategyType(redisCacheable.fetchStrategy()))
										.enablePreRefresh(redisCacheable.enablePreRefresh())
										.preRefreshThreshold(redisCacheable.preRefreshThreshold())
										.customStrategyClass(nullToEmpty(redisCacheable.customStrategyClass()))
										.build())
						.build();

		for (String cacheName : cacheNames) {
			if (cacheName == null || cacheName.isBlank()) continue;
			registry.register(cacheName.trim(), key, cachedInvocation);
			log.debug(
					"Registered CachedInvocation for cache={}, method={}, key={}",
					cacheName,
					method.getName(),
					key);
		}
	}

	private Object resolveCacheKey(
			Object targetBean, Method method, Object[] arguments, RedisCacheable redisCacheable) {
		return KeyResolver.resolveKey(targetBean, method, arguments, redisCacheable.keyGenerator());
	}

	private CachedInvocationContext.FetchStrategyType parseFetchStrategyType(String strategyType) {
		if (strategyType == null || strategyType.trim().isEmpty()) {
			return CachedInvocationContext.FetchStrategyType.AUTO;
		}

		try {
			return CachedInvocationContext.FetchStrategyType.valueOf(strategyType.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			log.warn("Invalid fetch strategy type: {}, defaulting to AUTO", strategyType);
			return CachedInvocationContext.FetchStrategyType.AUTO;
		}
	}

	private Method getSpecificMethod(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
		Object target = joinPoint.getTarget();
		String methodName = joinPoint.getSignature().getName();
		Class<?>[] parameterTypes =
				((MethodSignature) joinPoint.getSignature()).getMethod().getParameterTypes();
		return target.getClass().getMethod(methodName, parameterTypes);
	}
}
