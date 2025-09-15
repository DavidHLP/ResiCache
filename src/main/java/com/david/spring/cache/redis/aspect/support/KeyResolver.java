package com.david.spring.cache.redis.aspect.support;

import com.david.spring.cache.redis.reflect.support.ContextBeanSupport;

import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

public final class KeyResolver {

    private KeyResolver() {}

    public static Object resolveKey(
            Object targetBean, Method method, Object[] arguments, String keyGeneratorBeanName) {
        try {
            KeyGenerator generator =
                    ContextBeanSupport.resolveKeyGenerator(null, keyGeneratorBeanName);
            if (generator != null) {
                return generator.generate(targetBean, method, arguments);
            }
        } catch (Exception ignore) {
        }

        return SimpleKeyGenerator.generateKey(arguments);
    }

    public static String[] getCacheNames(String[] values, String[] cacheNames) {
        Set<String> list = new LinkedHashSet<>();
        for (String v : values) if (v != null && !v.isBlank()) list.add(v);
        for (String v : cacheNames) if (v != null && !v.isBlank()) list.add(v);
        return list.toArray(String[]::new);
    }
}
