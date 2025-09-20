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
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class CacheExpireTime implements ApplicationListener<ContextRefreshedEvent> {

    private final CacheUtil cacheUtil;
    private final AtomicInteger processedBeans = new AtomicInteger(0);
    private final AtomicInteger processedMethods = new AtomicInteger(0);

    public CacheExpireTime(CacheUtil cacheUtil) {
        this.cacheUtil = cacheUtil;
    }

    @Override
    public void onApplicationEvent(@NonNull ContextRefreshedEvent event) {
        log.debug("Starting cache expiration time initialization");
        long startTime = System.currentTimeMillis();

        processedBeans.set(0);
        processedMethods.set(0);

        event.getApplicationContext()
                .getBeansWithAnnotation(Component.class)
                .values()
                .forEach(this::processBean);

        cacheUtil.initializeCaches();

        long duration = System.currentTimeMillis() - startTime;
        log.info("Cache expiration time initialization completed in {}ms. Processed {} beans, {} methods",
                duration, processedBeans.get(), processedMethods.get());
    }

    private void processBean(Object bean) {
        try {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            processedBeans.incrementAndGet();

            for (Method method : targetClass.getMethods()) {
                processMethod(method, targetClass);
            }
        } catch (Exception e) {
            log.warn("Failed to process bean: {}, error: {}", bean.getClass().getSimpleName(), e.getMessage());
        }
    }

    private void processMethod(Method method, Class<?> targetClass) {
        try {
            Method specificMethod = ClassUtils.getMostSpecificMethod(method, targetClass);
            Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(specificMethod);
            RedisCacheable redisCacheable = AnnotatedElementUtils.findMergedAnnotation(bridgedMethod, RedisCacheable.class);

            if (redisCacheable != null) {
                cacheUtil.initExpireTime(redisCacheable);
                processedMethods.incrementAndGet();
                log.trace("Processed cache annotation on method: {}.{}",
                        targetClass.getSimpleName(), method.getName());
            }
        } catch (Exception e) {
            log.warn("Failed to process method {}.{}: {}",
                    targetClass.getSimpleName(), method.getName(), e.getMessage());
        }
    }
}
