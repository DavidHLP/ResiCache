package com.david.spring.cache.redis.support;

import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/** 统一的 Key 解析工具：优先指定的 KeyGenerator Bean，其次默认 KeyGenerator Bean，最后回退 SimpleKeyGenerator。 */
public final class KeyResolver {

    private KeyResolver() {}

    public static Object resolveKey(
            Object targetBean,
            Method method,
            Object[] arguments,
            String keyGeneratorBeanName,
            ApplicationContext applicationContext,
            KeyGenerator defaultKeyGenerator) {
        // 1) 优先使用指定的 KeyGenerator Bean
        if (keyGeneratorBeanName != null && !keyGeneratorBeanName.isBlank()) {
            try {
                KeyGenerator generator = applicationContext.getBean(keyGeneratorBeanName, KeyGenerator.class);
                return generator.generate(targetBean, method, arguments);
            } catch (Exception ignore) {
            }
        }

        // 2) 使用默认的 KeyGenerator Bean
        if (defaultKeyGenerator != null) {
            try {
                return defaultKeyGenerator.generate(targetBean, method, arguments);
            } catch (Exception ignore) {
            }
        }

        // 3) 兜底 SimpleKey 语义
        return org.springframework.cache.interceptor.SimpleKeyGenerator.generateKey(arguments);
    }

    public static String[] getCacheNames(String[] values, String[] cacheNames) {
        Set<String> list = new LinkedHashSet<>();
        for (String v : values) if (v != null && !v.isBlank()) list.add(v);
        for (String v : cacheNames) if (v != null && !v.isBlank()) list.add(v);
        return list.toArray(String[]::new);
    }
}
