package com.david.spring.cache.redis.register;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.annotation.RedisCaching;
import com.david.spring.cache.redis.register.operation.RedisCacheableOperation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;

/** 在Spring Bean初始化后扫描所有方法， 找出自定义的缓存注解并一次性注册它们。 这样就避免了在每次方法调用时都使用AOP来重复注册。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheOperationRegisterer implements BeanPostProcessor {

    private final RedisCacheRegister redisCacheRegister;
    private final KeyGenerator keyGenerator; // 假设你需要它来预处理某些信息

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        final Class<?> targetClass = bean.getClass();

        // 使用Spring的工具类来遍历bean上的所有方法
        ReflectionUtils.doWithMethods(
                targetClass,
                method -> {
                    // 处理 @RedisCacheable 和 @RedisCaching(cacheable)
                    RedisCacheable cacheable = method.getAnnotation(RedisCacheable.class);
                    if (cacheable != null) {
                        registerCacheableOperation(bean, method, cacheable);
                    }

                    RedisCaching caching = method.getAnnotation(RedisCaching.class);
                    if (caching != null) {
                        for (RedisCacheable c : caching.redisCacheable()) {
                            registerCacheableOperation(bean, method, c);
                        }
                        for (RedisCacheEvict e : caching.redisCacheEvict()) {
                            registerCacheEvictOperation(bean, method, e);
                        }
                    }

                    // 处理 @RedisCacheEvict
                    RedisCacheEvict cacheEvict = method.getAnnotation(RedisCacheEvict.class);
                    if (cacheEvict != null) {
                        registerCacheEvictOperation(bean, method, cacheEvict);
                    }
                },
                ReflectionUtils.USER_DECLARED_METHODS); // 只扫描用户声明的方法

        return bean; // 必须返回原始bean
    }

    private void registerCacheableOperation(
            Object bean, Method method, RedisCacheable redisCacheable) {
        try {
            // 注意：在启动阶段，我们无法获得实际的方法参数值，
            // 所以这里注册的是“元数据”或“模板”。
            // Key的生成逻辑可能需要调整，或者只注册Key的SpEL表达式。
            String[] cacheNames =
                    resolveCacheNames(redisCacheable.cacheNames(), redisCacheable.value());

            // 示例：这里我们只注册方法名和缓存名作为元数据
            RedisCacheableOperation operation =
                    RedisCacheableOperation.builder()
                            .name(method.toGenericString()) // 使用更唯一的方法签名
                            .cacheNames(cacheNames)
                            // .key(...) 这里不能生成具体的key，因为没有运行时参数
                            .build();

            redisCacheRegister.registerCacheableOperation(operation);
            log.debug("Registered cacheable operation for method: {}", method.getName());
        } catch (Exception e) {
            log.error("Failed to register cache operation for method {}", method.getName(), e);
        }
    }

    private void registerCacheEvictOperation(
            Object bean, Method method, RedisCacheEvict cacheEvict) {
        // 实现与上面类似
    }

    private String[] resolveCacheNames(String[] cacheNames, String[] values) {
        return cacheNames.length > 0 ? values : cacheNames;
    }
}
