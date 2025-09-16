package com.david.spring.cache.redis.aspect.support;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.EvictInvocation;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import com.david.spring.cache.redis.registry.EvictInvocationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;

import java.lang.reflect.Method;

import static com.david.spring.cache.redis.aspect.support.KeyResolver.getCacheNames;

@Slf4j
public final class RegisterUtil {
	public static void registerCachingInvocations(CacheInvocationRegistry cacheRegistry, ProceedingJoinPoint joinPoint, Method method,
	                                              Object targetBean, Object[] arguments,
	                                              RedisCacheable redisCacheable, Object key) {
		String[] cacheNames = getCacheNames(redisCacheable.value(), redisCacheable.cacheNames());
		CachedInvocation cachedInvocation = CachedInvocation.builder()
				.arguments(arguments)
				.targetBean(targetBean)
				.targetMethod(method)
				.cachedInvocationContext(
						CachedInvocation.CachedInvocationContext.builder()
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
								.build())
				.build();

		for (String cacheName : cacheNames) {
			if (cacheName == null || cacheName.isBlank())
				continue;
			cacheRegistry.register(cacheName.trim(), key, cachedInvocation);
			log.debug("Registered CachedInvocation from @RedisCaching for cache={}, method={}, key={}",
					cacheName, method.getName(), key);
		}
	}

	public static void registerEvictInvocation(EvictInvocationRegistry evictRegistry, ProceedingJoinPoint joinPoint, Method method,
	                                           Object targetBean, Object[] arguments,
	                                           RedisCacheEvict redisCacheEvict, Object key) {
		String[] cacheNames = getCacheNames(redisCacheEvict.value(), redisCacheEvict.cacheNames());

		boolean allEntries = redisCacheEvict.allEntries();
		if (allEntries) {
			key = null;
		}

		EvictInvocation invocation = EvictInvocation.builder()
				.arguments(arguments)
				.targetBean(targetBean)
				.targetMethod(method)
				.evictInvocationContext(
						new EvictInvocation.EvictInvocationContext(
								redisCacheEvict.value(),
								redisCacheEvict.cacheNames(),
								nullToEmpty(redisCacheEvict.key()),
								redisCacheEvict.keyGenerator(),
								redisCacheEvict.cacheManager(),
								redisCacheEvict.cacheResolver(),
								nullToEmpty(redisCacheEvict.condition()),
								redisCacheEvict.allEntries(),
								redisCacheEvict.beforeInvocation(),
								redisCacheEvict.sync()))
				.build();

		for (String cacheName : cacheNames) {
			if (cacheName == null || cacheName.isBlank())
				continue;
			evictRegistry.register(cacheName.trim(), key, invocation);
			log.debug("Registered EvictInvocation from @RedisCaching for cache={}, method={}, key={}, allEntries={}, beforeInvocation={}",
					cacheName, method.getName(), key, allEntries,
					invocation.getEvictInvocationContext() == null ? null : invocation.getEvictInvocationContext().beforeInvocation());
		}
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}
}
