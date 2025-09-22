package com.david.spring.cache.redis.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Redis Pro缓存Key管理器
 * 统一管理所有与缓存Key相关的操作
 */
@Slf4j
@Component
public class RedisProKeyManager {

	private final RedisProBeanResolver beanResolver;

	public RedisProKeyManager(RedisProBeanResolver beanResolver) {
		this.beanResolver = beanResolver;
	}

	/**
	 * 通过指定的KeyGenerator Bean生成缓存Key
	 *
	 * @param targetBean           目标Bean实例
	 * @param method               目标方法
	 * @param arguments            方法参数
	 * @param keyGeneratorBeanName KeyGenerator Bean名称
	 * @return 生成的缓存Key
	 * @throws IllegalArgumentException 参数无效时抛出
	 * @throws IllegalStateException    KeyGenerator Bean获取失败时抛出
	 */
	public Object resolveKey(Object targetBean, Method method, Object[] arguments,
	                         String keyGeneratorBeanName) {
		validateArguments(targetBean, method, keyGeneratorBeanName);

		if (arguments == null) {
			arguments = new Object[0];
		}

		KeyGenerator generator = getKeyGeneratorBean(keyGeneratorBeanName, method);

		try {
			Object key = generator.generate(targetBean, method, arguments);
			log.debug("Generated key using KeyGenerator '{}' for method {}: {}",
					keyGeneratorBeanName, method.getName(), key);
			return key;
		} catch (Exception e) {
			log.error("KeyGenerator '{}' failed to generate key for method {}: {}",
					keyGeneratorBeanName, method.getName(), e.getMessage(), e);
			throw new RuntimeException("Key generation failed for method " + method.getName() +
					" using KeyGenerator '" + keyGeneratorBeanName + "'", e);
		}
	}

	/**
	 * 获取缓存名称数组
	 * 优先使用value，如果为空则使用cacheNames
	 */
	public String[] getCacheNames(String[] value, String[] cacheNames) {
		if (value != null && value.length > 0) {
			return value;
		}
		if (cacheNames != null && cacheNames.length > 0) {
			return cacheNames;
		}
		throw new IllegalArgumentException("Neither value nor cacheNames is specified");
	}

	/**
	 * 验证参数有效性
	 */
	private void validateArguments(Object targetBean, Method method, String keyGeneratorBeanName) {
		if (targetBean == null) {
			throw new IllegalArgumentException("Target bean cannot be null");
		}
		if (method == null) {
			throw new IllegalArgumentException("Method cannot be null");
		}
		if (keyGeneratorBeanName == null || keyGeneratorBeanName.isBlank()) {
			throw new IllegalArgumentException("KeyGenerator bean name cannot be null or blank");
		}
	}

	/**
	 * 获取KeyGenerator Bean实例
	 */
	private KeyGenerator getKeyGeneratorBean(String keyGeneratorBeanName, Method method) {
		return beanResolver.resolveKeyGenerator(keyGeneratorBeanName, null);
	}
}
