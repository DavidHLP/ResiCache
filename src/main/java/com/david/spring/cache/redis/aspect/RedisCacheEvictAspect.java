package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.aspect.support.CacheOperationExecutor;
import com.david.spring.cache.redis.reflect.context.EvictInvocationContext;
import com.david.spring.cache.redis.aspect.support.KeyResolver;
import com.david.spring.cache.redis.reflect.EvictInvocation;
import com.david.spring.cache.redis.registry.factory.RegistryFactory;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class RedisCacheEvictAspect extends AbstractCacheAspect {

	private final RegistryFactory registryFactory;
	private final CacheOperationExecutor cacheOperationExecutor;
	private RedisCacheEvict currentAnnotation;

	public RedisCacheEvictAspect(RegistryFactory registryFactory, CacheOperationExecutor cacheOperationExecutor) {
		this.registryFactory = registryFactory;
		this.cacheOperationExecutor = cacheOperationExecutor;
	}

	@Around("@annotation(redisCacheEvict)")
	public Object around(ProceedingJoinPoint joinPoint, RedisCacheEvict redisCacheEvict) throws Throwable {
		this.currentAnnotation = redisCacheEvict;

		// 1. 注册调用信息
		registerInvocation(joinPoint, "evict");

		// 2. 执行缓存清除逻辑
		AspectExecutionContext context = extractExecutionContext(joinPoint);
		return cacheOperationExecutor.executeCacheEvict(joinPoint, redisCacheEvict, context);
	}

	@Override
	protected void processInvocation(ProceedingJoinPoint joinPoint) throws Exception {
		AspectExecutionContext context = extractExecutionContext(joinPoint);

		String[] cacheNames = KeyResolver.getCacheNames(
				currentAnnotation.value(), currentAnnotation.cacheNames());

		Object cacheKey = null;
		if (!currentAnnotation.allEntries()) {
			// 只使用keyGenerator生成缓存key
			cacheKey = KeyResolver.resolveKey(context.targetBean(), context.method(),
					context.arguments(), currentAnnotation.keyGenerator());
		}

		boolean allEntries = currentAnnotation.allEntries();

		EvictInvocation invocation = buildEvictInvocation(context, currentAnnotation);

		registerForCaches(cacheNames, cacheKey, context.method(),
				(cacheName, key) -> {
					registryFactory.getEvictInvocationRegistry().register(cacheName, key, invocation);
					log.debug("Registered EvictInvocation for cache={}, method={}, key={}, allEntries={}, beforeInvocation={}",
							cacheName, context.method().getName(), key, allEntries,
							invocation.getEvictInvocationContext() == null
									? null
									: invocation.getEvictInvocationContext().beforeInvocation());
				});
	}

	private EvictInvocation buildEvictInvocation(AspectExecutionContext context, RedisCacheEvict annotation) {
		return EvictInvocation.builder()
				.arguments(context.arguments())
				.targetBean(context.targetBean())
				.targetMethod(context.method())
				.evictInvocationContext(buildEvictInvocationContext(annotation))
				.build();
	}

	private EvictInvocationContext buildEvictInvocationContext(RedisCacheEvict annotation) {
		return EvictInvocationContext.builder()
				.value(annotation.value())
				.cacheNames(annotation.cacheNames())
				.key(safeString(annotation.key()))
				.keyGenerator(annotation.keyGenerator())
				.cacheManager(annotation.cacheManager())
				.cacheResolver(annotation.cacheResolver())
				.condition(safeString(annotation.condition()))
				.allEntries(annotation.allEntries())
				.beforeInvocation(annotation.beforeInvocation())
				.sync(annotation.sync())
				.build();
	}

	private String safeString(String value) {
		return value == null ? "" : value;
	}
}
