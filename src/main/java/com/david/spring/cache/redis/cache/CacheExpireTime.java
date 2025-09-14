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

/**
 * 缓存过期时间设置实现类
 *
 * <p>该类实现ApplicationListener接口，监听ContextRefreshedEvent事件，
 * 当Spring容器初始化完成后，遍历所有Bean，处理带有@RedisCacheable注解的方法， 初始化缓存过期时间。
 *
 * @author david
 * @version 1.0.0
 * @since 2025-09-14
 */
@Component
public class CacheExpireTime implements ApplicationListener<ContextRefreshedEvent> {

    private final CacheUtil cacheUtil;

    /**
     * 构造方法
     *
     * @param cacheUtil 缓存工具类实例
     */
    public CacheExpireTime(CacheUtil cacheUtil) {
        this.cacheUtil = cacheUtil;
    }

    /**
     * 处理Spring容器刷新事件
     *
     * <p>在Spring容器初始化完成后，扫描所有带有@Component注解的Bean， 并处理这些Bean中带有@RedisCacheable注解的方法，初始化缓存过期时间。
     *
     * @param event 容器刷新事件
     */
    @Override
    public void onApplicationEvent(@NonNull ContextRefreshedEvent event) {
        event.getApplicationContext()
                .getBeansWithAnnotation(Component.class)
                .values()
                .forEach(this::processBean);
        cacheUtil.initializeCaches();
    }

    /**
     * 处理单个Bean，扫描其方法上的@RedisCacheable注解
     *
     * @param bean 要处理的Bean实例
     */
    private void processBean(Object bean) {
        Class<?> targetClass = AopUtils.getTargetClass(bean);

        for (Method method : targetClass.getMethods()) {
            Method specificMethod = ClassUtils.getMostSpecificMethod(method, targetClass);
            Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(specificMethod);
            RedisCacheable redisCacheable =
                    AnnotatedElementUtils.findMergedAnnotation(bridgedMethod, RedisCacheable.class);
            if (redisCacheable != null) {
                cacheUtil.initExpireTime(redisCacheable);
            }
        }
    }
}
