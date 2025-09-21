package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.aspect.support.CacheOperationExecutor;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.aspect.support.KeyResolver;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.registry.factory.RegistryFactory;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class RedisCacheableAspect extends AbstractCacheAspect {

	private final RegistryFactory registryFactory;
	private final CacheOperationExecutor cacheOperationExecutor;
	private RedisCacheable currentAnnotation;

	public RedisCacheableAspect(RegistryFactory registryFactory, CacheOperationExecutor cacheOperationExecutor) {
		this.registryFactory = registryFactory;
		this.cacheOperationExecutor = cacheOperationExecutor;
	}

	@Around("@annotation(redisCacheable)")
	public Object around(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable) throws Throwable {
		this.currentAnnotation = redisCacheable;

		// 1. 注册调用信息
		registerInvocation(joinPoint, "cache");

		// 2. 执行缓存逻辑
		AspectExecutionContext context = extractExecutionContext(joinPoint);
		return cacheOperationExecutor.executeCacheable(joinPoint, redisCacheable, context);
	}

	@Override
	protected void processInvocation(ProceedingJoinPoint joinPoint) throws Exception {
		AspectExecutionContext context = extractExecutionContext(joinPoint);

		String[] cacheNames = KeyResolver.getCacheNames(
				currentAnnotation.value(), currentAnnotation.cacheNames());

		// 只使用keyGenerator生成缓存key
		Object cacheKey = KeyResolver.resolveKey(context.targetBean(), context.method(),
				context.arguments(), currentAnnotation.keyGenerator());

		CachedInvocation cachedInvocation = buildCachedInvocation(context, currentAnnotation);

		registerForCaches(cacheNames, cacheKey, context.method(),
				(cacheName, key) -> {
					registryFactory.getCacheInvocationRegistry().register(cacheName, key, cachedInvocation);
					log.debug("Registered CachedInvocation for cache={}, method={}, key={}",
							cacheName, context.method().getName(), key);
				});
	}

	private CachedInvocation buildCachedInvocation(AspectExecutionContext context, RedisCacheable annotation) {
		CachedInvocationContext invocationContext = buildCachedInvocationContext(annotation);
		return CachedInvocation.builder()
				.arguments(context.arguments())
				.targetBean(context.targetBean())
				.targetMethod(context.method())
				.cachedInvocationContext(invocationContext)
				.build();
	}

	private CachedInvocationContext buildCachedInvocationContext(RedisCacheable annotation) {
		if (annotation == null) {
			throw new IllegalArgumentException("RedisCacheable annotation cannot be null");
		}

		return CachedInvocationContext.builder()
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
				.distributedLockName(safeString(annotation.distributedLockName()))
				.internalLock(annotation.internalLock())
				.useSecondLevelCache(annotation.useSecondLevelCache())
				.fetchStrategy(parseFetchStrategyType(annotation.fetchStrategy()))
				.enablePreRefresh(annotation.enablePreRefresh())
				.preRefreshThreshold(annotation.preRefreshThreshold())
				.customStrategyClass(safeString(annotation.customStrategyClass()))
				.build();
	}

	private String safeString(String value) {
		return value == null ? "" : value;
	}

	private CachedInvocationContext.FetchStrategyType parseFetchStrategyType(String strategyType) {
		if (strategyType == null || strategyType.trim().isEmpty()) {
			return CachedInvocationContext.FetchStrategyType.AUTO;
		}

		try {
			return CachedInvocationContext.FetchStrategyType.valueOf(
					strategyType.trim().toUpperCase()
			);
		} catch (IllegalArgumentException e) {
			log.warn("Invalid fetch strategy type: {}, defaulting to AUTO", strategyType);
			return CachedInvocationContext.FetchStrategyType.AUTO;
		}
	}
}
