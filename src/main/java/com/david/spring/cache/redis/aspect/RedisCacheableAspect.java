package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.aspect.support.CacheAspectSupport;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.RegistryFactory;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
public class RedisCacheableAspect extends AbstractCacheAspect {

	private final RegistryFactory registryFactory;
	private final CacheAspectSupport cacheSupport;

	public RedisCacheableAspect(RegistryFactory registryFactory, CacheAspectSupport cacheSupport) {
		this.registryFactory = registryFactory;
		this.cacheSupport = cacheSupport;
	}

	@Around("@annotation(redisCacheable)")
	public Object around(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable) throws Throwable {
		Method method = extractTargetMethod(joinPoint);
		Object targetBean = joinPoint.getTarget();
		Object[] arguments = joinPoint.getArgs();

		// 注册调用信息
		registerInvocation(redisCacheable, method, targetBean, arguments);

		// 执行缓存逻辑
		return cacheSupport.executeCacheable(joinPoint, redisCacheable, method, targetBean, arguments);
	}

	private void registerInvocation(RedisCacheable annotation, Method method, Object targetBean, Object[] arguments) {
		try {
			String[] cacheNames = cacheSupport.keyManager.getCacheNames(annotation.value(), annotation.cacheNames());
			Object cacheKey = cacheSupport.keyManager.resolveKey(targetBean, method, arguments, annotation.keyGenerator());

			CachedInvocation cachedInvocation = buildCachedInvocation(method, targetBean, arguments, annotation);

			cacheSupport.processValidCacheNames(cacheNames, method, cacheName -> {
				registryFactory.getCacheInvocationRegistry().register(cacheName, cacheKey, cachedInvocation);
				log.debug("注册缓存调用: cache={}, method={}, key={}", cacheName, method.getName(), cacheKey);
			});
		} catch (Exception e) {
			log.warn("注册缓存调用失败: method={}, error={}", method.getName(), e.getMessage(), e);
		}
	}

	private CachedInvocation buildCachedInvocation(Method method, Object targetBean, Object[] arguments, RedisCacheable annotation) {
		CachedInvocationContext context = CachedInvocationContext.builder()
				.value(annotation.value())
				.cacheNames(annotation.cacheNames())
				.key(safeString(annotation.key()))
				.keyGenerator(annotation.keyGenerator())
				.cacheManager(annotation.cacheManager())
				.cacheResolver(annotation.cacheResolver())
				.condition(safeString(annotation.condition()))
				.unless(safeString(annotation.unless()))
				.sync(annotation.sync())
				.ttl(annotation.ttl())
				.type(annotation.type())
				.useBloomFilter(annotation.useBloomFilter())
				.randomTtl(annotation.randomTtl())
				.variance(annotation.variance())
				.cacheNullValues(annotation.cacheNullValues())
				.distributedLock(annotation.distributedLock())
				.internalLock(annotation.internalLock())
				.useSecondLevelCache(annotation.useSecondLevelCache())
				.enablePreRefresh(annotation.enablePreRefresh())
				.preRefreshThreshold(annotation.preRefreshThreshold())
				.build();

		return CachedInvocation.builder()
				.arguments(arguments)
				.targetBean(targetBean)
				.targetMethod(method)
				.cachedInvocationContext(context)
				.build();
	}

}