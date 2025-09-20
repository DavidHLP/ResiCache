package com.david.spring.cache.redis.aspect.support;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 缓存名称处理工具类
 * 提供缓存名称验证、清理和注册的通用逻辑
 */
@Slf4j
public final class CacheNameUtils {

	private CacheNameUtils() {
		// 工具类禁止实例化
	}

	/**
	 * 验证并处理缓存名称数组，对有效的缓存名称执行操作
	 *
	 * @param cacheNames 缓存名称数组
	 * @param key        缓存键
	 * @param method     目标方法
	 * @param action     对每个有效缓存名称执行的操作
	 */
	public static void processValidCacheNames(String[] cacheNames, Object key, Method method,
	                                          Consumer<String> action) {
		if (cacheNames == null || cacheNames.length == 0) {
			log.warn("Empty cache names array for method {}", method.getName());
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
			log.warn("No valid cache names found for method {}", method.getName());
		} else {
			log.debug("Processed {} cache names for method {}: {}",
					validNames.size(), method.getName(), validNames);
		}
	}

	/**
	 * 验证缓存名称是否有效
	 *
	 * @param cacheName 缓存名称
	 * @return 如果缓存名称有效则返回true
	 */
	public static boolean isValidCacheName(String cacheName) {
		return !AspectUtils.isBlankString(cacheName);
	}
}