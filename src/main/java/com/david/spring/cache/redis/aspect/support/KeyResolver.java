package com.david.spring.cache.redis.aspect.support;

import com.david.spring.cache.redis.support.BeanResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;

/**
 * Key解析器
 * 专门负责通过Bean容器获取KeyGenerator并生成缓存Key
 * 所有操作必须通过Bean容器，不提供任何保底策略
 */
@Slf4j
public final class KeyResolver {

	private KeyResolver() {
		// 工具类禁止实例化
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
	public static Object resolveKey(Object targetBean, Method method, Object[] arguments,
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
	 * 验证参数有效性
	 */
	private static void validateArguments(Object targetBean, Method method, String keyGeneratorBeanName) {
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
	 * 获取缓存名称数组
	 * 优先使用value，如果为空则使用cacheNames
	 */
	public static String[] getCacheNames(String[] value, String[] cacheNames) {
		if (value != null && value.length > 0) {
			return value;
		}
		if (cacheNames != null && cacheNames.length > 0) {
			return cacheNames;
		}
		throw new IllegalArgumentException("Neither value nor cacheNames is specified");
	}

	/**
	 * 获取KeyGenerator Bean实例
	 */
	private static KeyGenerator getKeyGeneratorBean(String keyGeneratorBeanName, Method method) {
		KeyGenerator generator = BeanResolver.resolveKeyGenerator(keyGeneratorBeanName, null);
		if (generator == null) {
			log.error("Critical error: KeyGenerator '{}' not found for method {}",
					keyGeneratorBeanName, method.getName());
			throw new IllegalStateException("KeyGenerator '" + keyGeneratorBeanName +
					"' not found for method " + method.getName());
		}
		return generator;
	}
}