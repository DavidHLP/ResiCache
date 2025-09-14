package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import com.david.spring.cache.redis.support.KeyResolver;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Redis缓存切面
 *
 * <p>本切面用于处理{@link RedisCacheable}注解，在方法执行前后进行缓存操作。 主要功能包括注册缓存调用信息、解析缓存键等。
 *
 * <p>使用示例：
 *
 * <pre>{@code
 * @RedisCacheable(value = "userCache", key = "#userId")
 * public User getUserById(Long userId) {
 *     // ... 方法实现
 * }
 * }</pre>
 *
 * @author David Huang [huangdawei0420@gmail.com]
 * @version 1.0
 * @since 2024-06-01
 * @see RedisCacheable
 * @see CacheInvocationRegistry
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RedisCacheableAspect {

    private final CacheInvocationRegistry registry;
    private final KeyGenerator keyGenerator;
    private final ApplicationContext applicationContext;

    // 统一通过 KeyResolver 解析 key

    public RedisCacheableAspect(
            CacheInvocationRegistry registry,
            KeyGenerator keyGenerator,
            ApplicationContext applicationContext) {
        this.registry = registry;
        this.keyGenerator = keyGenerator;
        this.applicationContext = applicationContext;
    }

    /**
     * 环绕通知方法，处理带有{@link RedisCacheable}注解的方法
     *
     * <p>此方法会在目标方法执行前注册缓存调用信息，然后执行目标方法
     *
     * @param joinPoint 连接点对象
     * @param redisCacheable Redis缓存注解实例
     * @return 目标方法的执行结果
     */
    @SneakyThrows
    @Around("@annotation(redisCacheable)")
    public Object around(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable) {
        try {
            registerInvocation(joinPoint, redisCacheable);
        } catch (Exception e) {
            log.warn("Failed to register cached invocation: {}", e.getMessage());
        }
        return joinPoint.proceed();
    }

    /**
     * 注册缓存调用信息
     *
     * <p>此方法负责解析方法签名和参数，构建缓存调用对象并注册到缓存注册表中
     *
     * @param joinPoint 连接点对象
     * @param redisCacheable Redis缓存注解实例
     * @throws NoSuchMethodException 如果无法找到对应方法
     */
    private void registerInvocation(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable)
            throws NoSuchMethodException {

        Method method = getSpecificMethod(joinPoint);
        Object targetBean = joinPoint.getTarget();
        Object[] arguments = joinPoint.getArgs();
        String[] cacheNames =
                KeyResolver.getCacheNames(redisCacheable.value(), redisCacheable.cacheNames());

        // 计算与 Spring Cache 一致的 Key：优先使用 SpEL key，其次使用（可能自定义的）KeyGenerator
        Object key = resolveCacheKey(targetBean, method, arguments, redisCacheable);

        CachedInvocation cachedInvocation =
                CachedInvocation.builder()
                        .arguments(arguments)
                        .targetBean(targetBean)
                        .targetMethod(method)
                        .build();

        for (String cacheName : cacheNames) {
            if (cacheName == null || cacheName.isBlank()) continue;
            registry.register(cacheName.trim(), key, cachedInvocation);
            log.debug(
                    "Registered CachedInvocation for cache={}, method={}, key={}",
                    cacheName,
                    method.getName(),
                    key);
        }
    }

    /**
     * 解析缓存键
     *
     * <p>使用统一的KeyResolver解析缓存键，优先使用SpEL表达式，其次使用KeyGenerator
     *
     * @param targetBean 目标Bean实例
     * @param method 目标方法
     * @param arguments 方法参数
     * @param redisCacheable Redis缓存注解实例
     * @return 解析后的缓存键
     */
    private Object resolveCacheKey(
            Object targetBean, Method method, Object[] arguments, RedisCacheable redisCacheable) {
        return KeyResolver.resolveKey(
                targetBean,
                method,
                arguments,
                redisCacheable.keyGenerator(),
                applicationContext,
                this.keyGenerator);
    }

    /**
     * 根据连接点获取具体方法
     *
     * <p>通过连接点信息获取目标类和方法签名，反射获取具体Method对象
     *
     * @param joinPoint 连接点对象
     * @return 目标方法对象
     * @throws NoSuchMethodException 如果目标类中不存在对应方法
     */
    private Method getSpecificMethod(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        Object target = joinPoint.getTarget();
        String methodName = joinPoint.getSignature().getName();
        Class<?>[] parameterTypes =
                ((MethodSignature) joinPoint.getSignature()).getMethod().getParameterTypes();
        return target.getClass().getMethod(methodName, parameterTypes);
    }
}
