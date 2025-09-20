package com.david.spring.cache.redis.aspect.support;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.aspect.AbstractCacheAspect;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.EvictInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.reflect.context.EvictInvocationContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 调用对象构建器工具类
 * 负责构建各种类型的 Invocation 对象和上下文
 */
@Slf4j
public final class InvocationBuilder {

	private InvocationBuilder() {
		// 工具类禁止实例化
	}

	/**
	 * 构建 CachedInvocation 对象
	 *
	 * @param context    切面执行上下文
	 * @param annotation 缓存注解
	 * @return 构建的 CachedInvocation
	 */
	public static CachedInvocation buildCachedInvocation(AbstractCacheAspect.AspectExecutionContext context,
	                                                     RedisCacheable annotation) {
		CachedInvocationContext invocationContext = buildCachedInvocationContext(annotation);
		return CachedInvocation.builder()
				.arguments(context.arguments())
				.targetBean(context.targetBean())
				.targetMethod(context.method())
				.cachedInvocationContext(invocationContext)
				.build();
	}

	/**
	 * 构建 EvictInvocation 对象
	 *
	 * @param context    切面执行上下文
	 * @param annotation 驱逐注解
	 * @return 构建的 EvictInvocation
	 */
	public static EvictInvocation buildEvictInvocation(AbstractCacheAspect.AspectExecutionContext context,
	                                                   RedisCacheEvict annotation) {
		return EvictInvocation.builder()
				.arguments(context.arguments())
				.targetBean(context.targetBean())
				.targetMethod(context.method())
				.evictInvocationContext(buildEvictInvocationContext(annotation))
				.build();
	}

	/**
	 * 构建 EvictInvocationContext 对象
	 *
	 * @param annotation 驱逐注解
	 * @return 构建的 EvictInvocationContext
	 */
	private static EvictInvocationContext buildEvictInvocationContext(RedisCacheEvict annotation) {
		return EvictInvocationContext.builder()
				.value(annotation.value())
				.cacheNames(annotation.cacheNames())
				.key(AspectUtils.safeString(annotation.key()))
				.keyGenerator(annotation.keyGenerator())
				.cacheManager(annotation.cacheManager())
				.cacheResolver(annotation.cacheResolver())
				.condition(AspectUtils.safeString(annotation.condition()))
				.allEntries(annotation.allEntries())
				.beforeInvocation(annotation.beforeInvocation())
				.sync(annotation.sync())
				.build();
	}

	/**
	 * 从 RedisCacheable 注解构建 CachedInvocationContext
	 *
	 * @param annotation RedisCacheable注解实例
	 * @return 构建的上下文对象
	 * @throws IllegalArgumentException 如果注解为null
	 */
	public static CachedInvocationContext buildCachedInvocationContext(RedisCacheable annotation) {
		if (annotation == null) {
			throw new IllegalArgumentException("RedisCacheable annotation cannot be null");
		}

		return CachedInvocationContext.builder()
				.value(annotation.value())
				.cacheNames(annotation.cacheNames())
				.key(AspectUtils.safeString(annotation.key()))
				.keyGenerator(annotation.keyGenerator())
				.cacheManager(annotation.cacheManager())
				.cacheResolver(annotation.cacheResolver())
				.condition(AspectUtils.safeString(annotation.condition()))
				.unless(AspectUtils.safeString(annotation.unless()))
				.sync(annotation.sync())
				.ttl(annotation.ttl())
				.type(annotation.type())
				.useBloomFilter(annotation.useBloomFilter())
				.randomTtl(annotation.randomTtl())
				.variance(annotation.variance())
				.cacheNullValues(annotation.cacheNullValues())
				.distributedLock(annotation.distributedLock())
				.distributedLockName(AspectUtils.safeString(annotation.distributedLockName()))
				.internalLock(annotation.internalLock())
				.useSecondLevelCache(annotation.useSecondLevelCache())
				.fetchStrategy(parseFetchStrategyType(annotation.fetchStrategy()))
				.enablePreRefresh(annotation.enablePreRefresh())
				.preRefreshThreshold(annotation.preRefreshThreshold())
				.customStrategyClass(AspectUtils.safeString(annotation.customStrategyClass()))
				.build();
	}

	/**
	 * 解析获取策略类型
	 *
	 * @param strategyType 策略类型字符串
	 * @return 解析后的策略类型枚举
	 */
	private static CachedInvocationContext.FetchStrategyType parseFetchStrategyType(String strategyType) {
		if (AspectUtils.isBlankString(strategyType)) {
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