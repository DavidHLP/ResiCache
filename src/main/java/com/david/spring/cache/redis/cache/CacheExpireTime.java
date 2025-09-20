package com.david.spring.cache.redis.cache;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.utils.CacheUtil;

import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

@Component
public class CacheExpireTime implements ApplicationListener<ContextRefreshedEvent> {

    private final CacheUtil cacheUtil;

    public CacheExpireTime(CacheUtil cacheUtil) {
        this.cacheUtil = cacheUtil;
    }

    @Override
    public void onApplicationEvent(@NonNull ContextRefreshedEvent event) {
        event.getApplicationContext()
                .getBeansWithAnnotation(Component.class)
                .values()
                .forEach(this::processBean);
        cacheUtil.initializeCaches();
    }

    private void processBean(Object bean) {
        Class<?> targetClass = AopUtils.getTargetClass(bean);

        for (Method method : targetClass.getMethods()) {
            Method specificMethod = ClassUtils.getMostSpecificMethod(method, targetClass);
            Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(specificMethod);
            RedisCacheable redisCacheable = AnnotatedElementUtils.findMergedAnnotation(bridgedMethod,
                    RedisCacheable.class);
            if (redisCacheable != null) {
                cacheUtil.initExpireTime(redisCacheable);
            }
        }
    }
}
