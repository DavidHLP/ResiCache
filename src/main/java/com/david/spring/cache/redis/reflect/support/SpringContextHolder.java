package com.david.spring.cache.redis.reflect.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
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
        if (ctx == null) {
            log.debug("Application context not available, cannot resolve bean: {}", name);
            return null;
        }
        try {
            T bean = ctx.getBean(name, requiredType);
            log.debug("Successfully resolved bean: {} of type: {}", name, requiredType.getSimpleName());
            return bean;
        } catch (Exception e) {
            log.debug("Failed to resolve bean: {} of type: {} - {}", name, requiredType.getSimpleName(), e.getMessage());
            return null;
        }
    }

    @Nullable
    public static <T> T getBean(Class<T> requiredType) {
        ApplicationContext ctx = context;
        if (ctx == null) {
            log.debug("Application context not available, cannot resolve bean of type: {}", requiredType.getSimpleName());
            return null;
        }
        try {
            T bean = ctx.getBean(requiredType);
            log.debug("Successfully resolved bean of type: {}", requiredType.getSimpleName());
            return bean;
        } catch (Exception e) {
            log.debug("Failed to resolve bean of type: {} - {}", requiredType.getSimpleName(), e.getMessage());
            return null;
        }
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext)
            throws BeansException {
        log.info("Setting Spring application context for CacheGuard components");
        SpringContextHolder.context = applicationContext;
        log.debug("Spring application context successfully set");
    }
}
