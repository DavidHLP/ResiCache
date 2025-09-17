package com.david.spring.cache.redis.aspect.support;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.aspect.constants.AspectConstants;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.EvictInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.reflect.context.EvictInvocationContext;
import com.david.spring.cache.redis.registry.impl.CacheInvocationRegistry;
import com.david.spring.cache.redis.registry.impl.EvictInvocationRegistry;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

import static com.david.spring.cache.redis.aspect.support.KeyResolver.getCacheNames;

/**
 * 缓存注册工具类，提供统一的缓存调用注册功能
 * 负责将缓存和驱逐操作注册到相应的注册中心
 *
 * @author David
 */
@Slf4j
public final class RegisterUtil {
	/**
	 * 私有构造函数，防止实例化
	 */
	private RegisterUtil() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	/**
	 * 注册缓存调用信息
	 *
	 * @param cacheRegistry  缓存调用注册中心
	 * @param method         目标方法
	 * @param targetBean     目标对象
	 * @param arguments      方法参数
	 * @param redisCacheable RedisCacheable注解实例
	 * @param key            解析后的缓存键
	 */
	public static void registerCachingInvocations(CacheInvocationRegistry cacheRegistry, Method method,
	                                              Object targetBean, Object[] arguments,
	                                              RedisCacheable redisCacheable, Object key) {
		String[] cacheNames = getCacheNames(redisCacheable.value(), redisCacheable.cacheNames());
		CachedInvocation cachedInvocation = CachedInvocation.builder()
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
								.useSecondLevelCache(redisCacheable.useSecondLevelCache())
								.internalLock(redisCacheable.internalLock())
								.useBloomFilter(redisCacheable.useBloomFilter())
								.cacheNullValues(redisCacheable.cacheNullValues())
								.distributedLock(redisCacheable.distributedLock())
								.randomTtl(redisCacheable.randomTtl())
								.variance(redisCacheable.variance())
								.build())
				.build();

		for (String cacheName : cacheNames) {
			if (isValidCacheName(cacheName)) {
				String trimmedCacheName = cacheName.trim();
				cacheRegistry.register(trimmedCacheName, key, cachedInvocation);
				log.debug(AspectConstants.LogMessages.CACHE_OPERATION_REGISTERED,
						"CachedInvocation", trimmedCacheName, method.getName(), key);
			}
		}
	}

	/**
	 * 注册缓存驱逐调用信息
	 *
	 * @param evictRegistry   缓存驱逐注册中心
	 * @param method          目标方法
	 * @param targetBean      目标对象
	 * @param arguments       方法参数
	 * @param redisCacheEvict RedisCacheEvict注解实例
	 * @param key             解析后的缓存键（如果是 allEntries 则为 null）
	 */
	public static void registerEvictInvocation(EvictInvocationRegistry evictRegistry, Method method,
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
						EvictInvocationContext.builder()
								.value(redisCacheEvict.value())
								.cacheNames(redisCacheEvict.cacheNames())
								.key(nullToEmpty(redisCacheEvict.key()))
								.keyGenerator(redisCacheEvict.keyGenerator())
								.cacheManager(redisCacheEvict.cacheManager())
								.cacheResolver(redisCacheEvict.cacheResolver())
								.condition(nullToEmpty(redisCacheEvict.condition()))
								.allEntries(redisCacheEvict.allEntries())
								.beforeInvocation(redisCacheEvict.beforeInvocation())
								.sync(redisCacheEvict.sync())
								.build())
				.build();

		for (String cacheName : cacheNames) {
			if (isValidCacheName(cacheName)) {
				String trimmedCacheName = cacheName.trim();
				evictRegistry.register(trimmedCacheName, key, invocation);
				log.debug("Registered EvictInvocation for cache={}, method={}, key={}, allEntries={}, beforeInvocation={}",
						trimmedCacheName, method.getName(), key, allEntries,
						invocation.getEvictInvocationContext() == null ? null : invocation.getEvictInvocationContext().beforeInvocation());
			}
		}
	}

	/**
	 * 将 null 字符串转换为空字符串
	 *
	 * @param s 输入字符串
	 * @return 非 null 的字符串
	 */
	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}

	/**
	 * 验证缓存名称是否有效
	 *
	 * @param cacheName 缓存名称
	 * @return 是否有效
	 */
	private static boolean isValidCacheName(String cacheName) {
		return cacheName != null && !cacheName.isBlank();
	}
}
