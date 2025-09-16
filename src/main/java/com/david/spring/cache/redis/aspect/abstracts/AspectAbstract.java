package com.david.spring.cache.redis.aspect.abstracts;

import com.david.spring.cache.redis.aspect.interfaces.AspectInterface;
import com.david.spring.cache.redis.aspect.support.KeyResolver;

import lombok.extern.slf4j.Slf4j;

import org.aspectj.lang.ProceedingJoinPoint;

import java.lang.reflect.Method;

/**
 * 抽象切面类，实现通用的切面功能
 */
@Slf4j
public abstract class AspectAbstract implements AspectInterface {

    @Override
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = getSpecificMethod(joinPoint);
        log.debug("Processing aspect for method: {}.{}",
                method.getDeclaringClass().getSimpleName(), method.getName());
        try {
            registerInvocation(joinPoint);
        } catch (Exception e) {
            log.error("Registration failed for method: {}.{} - {}",
                    method.getDeclaringClass().getSimpleName(), method.getName(), e.getMessage(), e);
            handleRegistrationException(e);
        }
        return joinPoint.proceed();
    }

    /**
     * 处理注册异常的策略方法，子类可以重写
     * 
     * @param e 异常信息
     */
    protected void handleRegistrationException(Exception e) {
        log.warn("Failed to register cache invocation, cache operations may be affected: {}", e.getMessage());
    }

    /**
     * 解析缓存键
     * 
     * @param targetBean           目标对象
     * @param method               目标方法
     * @param arguments            方法参数
     * @param keyGeneratorBeanName KeyGenerator Bean 名称
     * @return 缓存键
     */
    protected Object resolveCacheKey(Object targetBean, Method method, Object[] arguments,
            String keyGeneratorBeanName) {
        return KeyResolver.resolveKey(targetBean, method, arguments, keyGeneratorBeanName);
    }

    /**
     * 获取缓存名称数组
     * 
     * @param values     缓存值数组
     * @param cacheNames 缓存名称数组
     * @return 合并后的缓存名称数组
     */
    protected String[] getCacheNames(String[] values, String[] cacheNames) {
        return KeyResolver.getCacheNames(values, cacheNames);
    }
}
