package com.david.spring.cache.redis.parser;

import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

/**
 * 缓存名称生成器
 * 用于生成缓存名称
 *
 * @author david
 */
@Component
public class CacheNameGenerator {

	/**
	 * 获取缓存名称
	 *
	 * @param cacheNames 注解中配置的缓存名称数组
	 * @param valueNames 注解中配置的value数组（cacheNames的别名）
	 * @return 缓存名称
	 */
	public String getCacheName(String[] cacheNames, String[] valueNames) {
		Assert.notNull(cacheNames, "cacheNames cannot be null");
		Assert.notNull(valueNames, "valueNames cannot be null");
		// 优先使用cacheNames
		if (cacheNames.length > 0 && StringUtils.hasText(cacheNames[0])) {
			return cacheNames[0];
		}

		// 其次使用value（cacheNames的别名）
		if (valueNames.length > 0 && StringUtils.hasText(valueNames[0])) {
			return valueNames[0];
		}

		// 确保最终一定有返回值
		throw new IllegalArgumentException("No valid cache name found in cacheNames or valueNames");
	}

	/**
	 * 生成默认缓存名称
	 *
	 * @param targetClass 目标类
	 * @param method      方法
	 * @return 默认缓存名称
	 */
	public String generateDefaultCacheName(Class<?> targetClass, Method method) {
		return targetClass.getSimpleName() + "." + method.getName();
	}
}
