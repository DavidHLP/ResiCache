package com.david.spring.cache.redis.cache;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.utils.CacheUtil;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

@Slf4j
@Component
public class CacheExpireTime implements ApplicationListener<ContextRefreshedEvent> {

    private final CacheUtil cacheUtil;

    public CacheExpireTime(CacheUtil cacheUtil) {
        this.cacheUtil = cacheUtil;
    }

    @Override
    public void onApplicationEvent(@NonNull ContextRefreshedEvent event) {
        log.info("Starting to process cache expiration times...");
        event.getApplicationContext()
                .getBeansWithAnnotation(Component.class)
                .values()
                .forEach(this::processBean);
        cacheUtil.initializeCaches();
        log.info("Cache expiration time processing completed.");
    }

    private void processBean(Object bean) {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        log.debug("Processing bean: {}", targetClass.getName());
        for (Method method : targetClass.getMethods()) {
            Method specificMethod = ClassUtils.getMostSpecificMethod(method, targetClass);
            Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(specificMethod);
            RedisCacheable redisCacheable = AnnotatedElementUtils.findMergedAnnotation(bridgedMethod,
                    RedisCacheable.class);
            if (redisCacheable != null) {
                log.debug("Found RedisCacheable annotation on method: {}.{}", 
                        targetClass.getSimpleName(), method.getName());
                cacheUtil.initExpireTime(redisCacheable);
            }
        }
    }
}
