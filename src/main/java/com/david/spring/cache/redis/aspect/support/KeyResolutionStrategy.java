package com.david.spring.cache.redis.aspect.support;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.aspect.AbstractCacheAspect;

/**
 * 缓存键解析策略工具类
 * 提供统一的键解析逻辑
 */
public final class KeyResolutionStrategy {

	private KeyResolutionStrategy() {
		// 工具类禁止实例化
	}

	/**
	 * 解析缓存键 - 适用于 @RedisCacheable 注解
	 *
	 * @param context    切面执行上下文
	 * @param annotation 缓存注解
	 * @return 解析的缓存键
	 */
	public static Object resolveCacheKey(AbstractCacheAspect.AspectExecutionContext context,
	                                     RedisCacheable annotation) {
		return KeyResolver.resolveKey(context.targetBean(), context.method(),
				context.arguments(), annotation.keyGenerator());
	}

	/**
	 * 解析驱逐键 - 适用于 @RedisCacheEvict 注解
	 *
	 * @param context    切面执行上下文
	 * @param annotation 驱逐注解
	 * @return 解析的驱逐键，如果是 allEntries 则返回 null
	 */
	public static Object resolveEvictKey(AbstractCacheAspect.AspectExecutionContext context,
	                                     RedisCacheEvict annotation) {
		if (annotation.allEntries()) {
			return null;
		}

		// 优先使用 SpEL 表达式，然后回退到 keyGenerator
		Object resolvedKey = null;
		if (annotation.key() != null && !annotation.key().isBlank()) {
			resolvedKey = KeyResolver.resolveKeySpEL(context.targetBean(), context.method(),
					context.arguments(), annotation.key());
		}

		if (resolvedKey == null) {
			resolvedKey = KeyResolver.resolveKey(context.targetBean(), context.method(),
					context.arguments(), annotation.keyGenerator());
		}

		return resolvedKey;
	}

	/**
	 * 提取缓存名称 - 通用方法
	 *
	 * @param values     值数组
	 * @param cacheNames 缓存名称数组
	 * @return 提取的缓存名称数组
	 */
	public static String[] extractCacheNames(String[] values, String[] cacheNames) {
		return KeyResolver.getCacheNames(values, cacheNames);
	}
}