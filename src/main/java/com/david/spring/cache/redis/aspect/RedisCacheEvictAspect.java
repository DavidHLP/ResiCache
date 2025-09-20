package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.aspect.support.InvocationBuilder;
import com.david.spring.cache.redis.aspect.support.KeyResolutionStrategy;
import com.david.spring.cache.redis.reflect.EvictInvocation;
import com.david.spring.cache.redis.registry.EvictInvocationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
public class RedisCacheEvictAspect extends AbstractCacheAspect {

	private final EvictInvocationRegistry registry;

	public RedisCacheEvictAspect(EvictInvocationRegistry registry) {
		this.registry = registry;
	}

	private RedisCacheEvict currentAnnotation;

	@Around("@annotation(redisCacheEvict)")
	public Object around(ProceedingJoinPoint joinPoint, RedisCacheEvict redisCacheEvict) throws Throwable {
		this.currentAnnotation = redisCacheEvict;
		return executeAroundAdvice(joinPoint, "evict");
	}

	@Override
	protected void processInvocation(ProceedingJoinPoint joinPoint) throws Exception {
		AspectExecutionContext context = extractExecutionContext(joinPoint);

		String[] cacheNames = KeyResolutionStrategy.extractCacheNames(
				currentAnnotation.value(), currentAnnotation.cacheNames());
		final Object key = KeyResolutionStrategy.resolveEvictKey(context, currentAnnotation);
		final boolean allEntries = currentAnnotation.allEntries();

		EvictInvocation invocation = InvocationBuilder.buildEvictInvocation(context, currentAnnotation);

		registerForCaches(cacheNames, key, context.method(),
				(cacheName, cacheKey) -> {
					registry.register(cacheName, cacheKey, invocation);
					log.debug(
							"Registered EvictInvocation for cache={}, method={}, key={}, allEntries={}, beforeInvocation={}",
							cacheName,
							context.method().getName(),
							cacheKey,
							allEntries,
							invocation.getEvictInvocationContext() == null
									? null
									: invocation.getEvictInvocationContext().beforeInvocation());
				});
	}
}
