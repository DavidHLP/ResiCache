package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.aspect.support.InvocationBuilder;
import com.david.spring.cache.redis.aspect.support.KeyResolutionStrategy;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
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

	private final CacheInvocationRegistry registry;


	public RedisCacheableAspect(CacheInvocationRegistry registry) {
		this.registry = registry;
	}

	private RedisCacheable currentAnnotation;

	@Around("@annotation(redisCacheable)")
	public Object around(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable) throws Throwable {
		this.currentAnnotation = redisCacheable;
		return executeAroundAdvice(joinPoint, "cache");
	}

	@Override
	protected void processInvocation(ProceedingJoinPoint joinPoint) throws Exception {
		AspectExecutionContext context = extractExecutionContext(joinPoint);

		String[] cacheNames = KeyResolutionStrategy.extractCacheNames(
				currentAnnotation.value(), currentAnnotation.cacheNames());
		Object cacheKey = KeyResolutionStrategy.resolveCacheKey(context, currentAnnotation);

		CachedInvocation cachedInvocation = InvocationBuilder.buildCachedInvocation(context, currentAnnotation);

		registerForCaches(cacheNames, cacheKey, context.method(),
				(cacheName, key) -> {
					registry.register(cacheName, key, cachedInvocation);
					log.debug("Registered CachedInvocation for cache={}, method={}, key={}",
							cacheName, context.method().getName(), key);
				});
	}
}
