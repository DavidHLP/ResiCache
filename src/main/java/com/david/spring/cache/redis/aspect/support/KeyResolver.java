package com.david.spring.cache.redis.aspect.support;

import com.david.spring.cache.redis.reflect.support.BeanResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 缓存键解析器，负责解析缓存操作的键值
 * 支持自定义键生成器和默认键生成策略
 *
 * @author David
 */
@Slf4j
public final class KeyResolver {

	/**
	 * 私有构造函数，防止实例化
	 */
	private KeyResolver() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	/**
	 * 解析缓存键
	 *
	 * @param targetBean           目标对象
	 * @param method               目标方法
	 * @param arguments            方法参数
	 * @param keyGeneratorBeanName 键生成器Bean名称
	 * @return 解析后的缓存键
	 */
	public static Object resolveKey(
			Object targetBean, Method method, Object[] arguments, String keyGeneratorBeanName) {
		log.debug("Resolving cache key for method: {} with keyGenerator: {}",
				method.getName(), keyGeneratorBeanName);

		// 尝试使用自定义键生成器
		if (StringUtils.hasText(keyGeneratorBeanName)) {
			try {
				KeyGenerator generator = BeanResolver.resolveKeyGenerator(null, keyGeneratorBeanName);
				if (generator != null) {
					Object key = generator.generate(targetBean, method, arguments);
					log.debug("Generated cache key using custom generator '{}': {}", keyGeneratorBeanName, key);
					return key;
				}
			} catch (Exception e) {
				log.warn("Failed to resolve custom key generator '{}', falling back to default: {}",
						keyGeneratorBeanName, e.getMessage());
			}
		}

		// 使用默认键生成器
		Object defaultKey = generateDefaultKey(arguments);
		log.debug("Generated cache key using SimpleKeyGenerator: {}", defaultKey);
		return defaultKey;
	}

	/**
	 * 合并并去重缓存名称数组
	 *
	 * @param values     值数组
	 * @param cacheNames 缓存名称数组
	 * @return 合并后的缓存名称数组
	 */
	public static String[] getCacheNames(String[] values, String[] cacheNames) {
		log.debug("Resolving cache names from values: {} and cacheNames: {}",
				java.util.Arrays.toString(values), java.util.Arrays.toString(cacheNames));

		Set<String> nameSet = new LinkedHashSet<>();

		// 添加 values 数组中的有效值
		addValidNames(nameSet, values);
		// 添加 cacheNames 数组中的有效值
		addValidNames(nameSet, cacheNames);

		String[] result = nameSet.toArray(String[]::new);
		log.debug("Resolved cache names: {}", java.util.Arrays.toString(result));
		return result;
	}

	/**
	 * 使用默认策略生成缓存键
	 *
	 * @param arguments 方法参数
	 * @return 生成的缓存键
	 */
	private static Object generateDefaultKey(Object[] arguments) {
		try {
			return SimpleKeyGenerator.generateKey(arguments);
		} catch (Exception e) {
			log.error("Failed to generate default cache key, using method name as fallback: {}", e.getMessage());
			return "fallback_key_" + System.currentTimeMillis();
		}
	}

	/**
	 * 向集合中添加有效的缓存名称
	 *
	 * @param nameSet 目标集合
	 * @param names   待添加的名称数组
	 */
	private static void addValidNames(Set<String> nameSet, String[] names) {
		if (names != null) {
			for (String name : names) {
				if (StringUtils.hasText(name)) {
					nameSet.add(name.trim());
				}
			}
		}
	}
}
