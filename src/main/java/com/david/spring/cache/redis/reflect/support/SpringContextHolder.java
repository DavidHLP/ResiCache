package com.david.spring.cache.redis.reflect.support;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/** 简单的 ApplicationContext 持有器，支持在任意位置按名称/类型获取 Bean。 仅用于惰性解析依赖（如 KeyGenerator、CacheResolver 等）。 */
@Component
public class SpringContextHolder implements ApplicationContextAware {

    private static volatile ApplicationContext context;

    public static boolean hasContext() {
        return context != null;
    }

    @Nullable
    public static ApplicationContext getContext() {
        return context;
    }

    @Nullable
    public static <T> T getBean(String name, Class<T> requiredType) {
        ApplicationContext ctx = context;
        if (ctx == null) return null;
        try {
            return ctx.getBean(name, requiredType);
        } catch (Exception ignore) {
            return null;
        }
    }

    @Nullable
    public static <T> T getBean(Class<T> requiredType) {
        ApplicationContext ctx = context;
        if (ctx == null) return null;
        try {
            return ctx.getBean(requiredType);
        } catch (Exception ignore) {
            return null;
        }
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext)
            throws BeansException {
        SpringContextHolder.context = applicationContext;
    }
}
