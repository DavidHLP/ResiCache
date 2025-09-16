package com.david.spring.cache.redis.aspect.support;

import com.david.spring.cache.redis.reflect.support.ContextBeanSupport;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
public final class KeyResolver {

	private KeyResolver() {}

	public static Object resolveKey(
			Object targetBean, Method method, Object[] arguments, String keyGeneratorBeanName) {
		log.debug("Resolving cache key for method: {} with keyGenerator: {}",
				method.getName(), keyGeneratorBeanName);
		try {
			KeyGenerator generator =
					ContextBeanSupport.resolveKeyGenerator(null, keyGeneratorBeanName);
			if (generator != null) {
				Object key = generator.generate(targetBean, method, arguments);
				log.debug("Generated cache key using custom generator: {}", key);
				return key;
			}
		} catch (Exception e) {
			log.warn("Failed to resolve custom key generator '{}', falling back to default: {}",
					keyGeneratorBeanName, e.getMessage());
		}

		Object defaultKey = SimpleKeyGenerator.generateKey(arguments);
		log.debug("Generated cache key using SimpleKeyGenerator: {}", defaultKey);
		return defaultKey;
	}

	public static String[] getCacheNames(String[] values, String[] cacheNames) {
		log.debug("Resolving cache names from values: {} and cacheNames: {}",
				java.util.Arrays.toString(values), java.util.Arrays.toString(cacheNames));
		Set<String> list = new LinkedHashSet<>();
		for (String v : values) if (v != null && !v.isBlank()) list.add(v);
		for (String v : cacheNames) if (v != null && !v.isBlank()) list.add(v);
		String[] result = list.toArray(String[]::new);
		log.debug("Resolved cache names: {}", java.util.Arrays.toString(result));
		return result;
	}
}
