package com.david.spring.cache.redis.aspect.support;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.core.RedisProKeyManager;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Redis Pro缓存服务
 * 提供缓存操作的核心功能
 */
@Slf4j
@Component
public class CacheAspectSupport {

	public final RedisProKeyManager keyManager;
	private final CacheManager cacheManager;

	public CacheAspectSupport(CacheManager cacheManager, RedisProKeyManager keyManager) {
		this.cacheManager = cacheManager;
		this.keyManager = keyManager;
	}

	/**
	 * 执行缓存读取逻辑
	 */
	public Object executeCacheable(ProceedingJoinPoint joinPoint, RedisCacheable annotation,
	                               Method method, Object targetBean, Object[] arguments) throws Throwable {
		String[] cacheNames = keyManager.getCacheNames(annotation.value(), annotation.cacheNames());
		Object key = keyManager.resolveKey(targetBean, method, arguments, annotation.keyGenerator());

		// 查找缓存
		for (String cacheName : cacheNames) {
			Cache cache = cacheManager.getCache(cacheName);
			if (cache != null) {
				Cache.ValueWrapper valueWrapper = cache.get(key);
				if (valueWrapper != null) {
					log.debug("缓存命中: key={} cache={}", key, cacheName);
					return valueWrapper.get();
				}
			}
		}

		// 缓存未命中，执行方法
		Object result = joinPoint.proceed();

		// 存入缓存
		for (String cacheName : cacheNames) {
			Cache cache = cacheManager.getCache(cacheName);
			if (cache != null) {
				cache.put(key, result);
				log.debug("缓存结果: key={} cache={}", key, cacheName);
			}
		}

		return result;
	}

	/**
	 * 执行缓存清除逻辑
	 */
	public Object executeCacheEvict(ProceedingJoinPoint joinPoint, RedisCacheEvict annotation,
	                                Method method, Object targetBean, Object[] arguments) throws Throwable {
		String[] cacheNames = keyManager.getCacheNames(annotation.value(), annotation.cacheNames());

		// 方法执行前清除
		if (annotation.beforeInvocation()) {
			performEviction(cacheNames, method, targetBean, arguments, annotation);
		}

		Object result;
		try {
			result = joinPoint.proceed();
		} catch (Throwable ex) {
			// 方法执行前清除时，方法失败则不再清除
			if (annotation.beforeInvocation()) {
				throw ex;
			}
			throw ex;
		}

		// 方法执行后清除
		if (!annotation.beforeInvocation()) {
			performEviction(cacheNames, method, targetBean, arguments, annotation);
		}

		return result;
	}

	/**
	 * 执行清除操作
	 */
	private void performEviction(String[] cacheNames, Method method, Object targetBean, Object[] arguments,
	                             RedisCacheEvict annotation) {
		for (String cacheName : cacheNames) {
			Cache cache = cacheManager.getCache(cacheName);
			if (cache != null) {
				if (annotation.allEntries()) {
					cache.clear();
					log.debug("清空缓存: cache={}", cacheName);
				} else {
					Object key = keyManager.resolveKey(targetBean, method, arguments, annotation.keyGenerator());
					cache.evict(key);
					log.debug("清除缓存: key={} cache={}", key, cacheName);
				}
			}
		}
	}

	// ========== 工具方法 ==========

	/**
	 * 检查字符串是否为空
	 */
	public boolean isBlankString(String value) {
		return value == null || value.trim().isEmpty();
	}

	/**
	 * 处理有效的缓存名称
	 */
	public void processValidCacheNames(String[] cacheNames, Method method,
	                                   Consumer<String> action) {
		if (cacheNames == null || cacheNames.length == 0) {
			log.warn("缓存名称数组为空: method={}", method.getName());
			return;
		}

		List<String> validNames = new ArrayList<>();
		for (String cacheName : cacheNames) {
			if (isValidCacheName(cacheName)) {
				String trimmedName = cacheName.trim();
				validNames.add(trimmedName);
				action.accept(trimmedName);
			}
		}

		if (validNames.isEmpty()) {
			log.warn("无有效缓存名称: method={}", method.getName());
		} else {
			log.debug("处理缓存名称数量: {} method: {} names: {}",
					validNames.size(), method.getName(), validNames);
		}
	}

	/**
	 * 验证缓存名称
	 */
	public boolean isValidCacheName(String cacheName) {
		return !isBlankString(cacheName);
	}
}