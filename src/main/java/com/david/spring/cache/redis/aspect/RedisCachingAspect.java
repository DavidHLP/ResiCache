package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.annotation.RedisCaching;
import com.david.spring.cache.redis.aspect.abstracts.AspectAbstract;
import com.david.spring.cache.redis.aspect.support.RegisterUtil;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import com.david.spring.cache.redis.registry.EvictInvocationRegistry;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5) // 优先级高于单个注解的切面
public class RedisCachingAspect extends AspectAbstract {

	private final CacheInvocationRegistry cacheRegistry;
	private final EvictInvocationRegistry evictRegistry;

	public RedisCachingAspect(CacheInvocationRegistry cacheRegistry, EvictInvocationRegistry evictRegistry) {
		this.cacheRegistry = cacheRegistry;
		this.evictRegistry = evictRegistry;
	}

	@SneakyThrows
	@Around("@annotation(redisCaching)")
	public Object around(ProceedingJoinPoint joinPoint, RedisCaching redisCaching) {
		try {
			registerCachingInvocations(joinPoint, redisCaching);
		} catch (Exception e) {
			handleRegistrationException(e);
		}
		return joinPoint.proceed();
	}

	@Override
	public void registerInvocation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
		// 此方法由带注解参数的 around 方法调用具体实现，这里不直接使用
		throw new UnsupportedOperationException("请使用带注解参数的 around 方法");
	}

	/**
	 * 处理 RedisCaching 组合注解中的所有缓存操作
	 */
	private void registerCachingInvocations(ProceedingJoinPoint joinPoint, RedisCaching redisCaching)
			throws NoSuchMethodException {

		Method method = getSpecificMethod(joinPoint);
		Object targetBean = joinPoint.getTarget();
		Object[] arguments = joinPoint.getArgs();

		// 处理 cacheable 注解数组
		for (RedisCacheable cacheable : redisCaching.cacheable()) {
			registerCacheableInvocation(joinPoint, method, targetBean, arguments, cacheable);
		}

		// 处理 evict 注解数组
		for (RedisCacheEvict evict : redisCaching.evict()) {
			registerEvictInvocation(joinPoint, method, targetBean, arguments, evict);
		}
	}

	/**
	 * 注册 Cacheable 调用信息
	 */
	private void registerCacheableInvocation(ProceedingJoinPoint joinPoint, Method method,
	                                         Object targetBean, Object[] arguments,
	                                         RedisCacheable redisCacheable) {
		Object key = resolveCacheKey(targetBean, method, arguments, redisCacheable.keyGenerator());
		RegisterUtil.registerCachingInvocations(cacheRegistry, joinPoint, method, targetBean, arguments, redisCacheable, key);
	}

	/**
	 * 注册 Evict 调用信息
	 */
	private void registerEvictInvocation(ProceedingJoinPoint joinPoint, Method method,
	                                     Object targetBean, Object[] arguments,
	                                     RedisCacheEvict redisCacheEvict) {

		boolean allEntries = redisCacheEvict.allEntries();
		Object key = null;
		if (!allEntries) {
			key = resolveCacheKey(targetBean, method, arguments, redisCacheEvict.keyGenerator());
		}
		RegisterUtil.registerEvictInvocation(evictRegistry, joinPoint, method, targetBean, arguments, redisCacheEvict, key);
	}
}