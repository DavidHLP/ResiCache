package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.reflect.EvictInvocation;
import com.david.spring.cache.redis.registry.EvictInvocationRegistry;
import com.david.spring.cache.redis.support.KeyResolver;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * RedisCacheEvictAspect 是 Spring AOP 切面，用于拦截带有 {@link RedisCacheEvict} 注解的方法，
 * 并注册缓存驱逐调用信息到注册表中。
 * <p>
 * 该切面会解析注解信息，构建驱逐调用对象（EvictInvocation），并将其注册到对应的缓存中。
 * </p>
 *
 * @author David Huang [huangda1984@gmail.com]
 * @version 1.0
 * @see RedisCacheEvict
 * @see EvictInvocation
 * @see EvictInvocationRegistry
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RedisCacheEvictAspect {

    private final EvictInvocationRegistry registry;
    private final KeyGenerator keyGenerator;
    private final ApplicationContext applicationContext;

    public RedisCacheEvictAspect(
            EvictInvocationRegistry registry,
            KeyGenerator keyGenerator,
            ApplicationContext applicationContext) {
        this.registry = registry;
        this.keyGenerator = keyGenerator;
        this.applicationContext = applicationContext;
    }

    /**
     * 环绕通知，用于处理带有 @RedisCacheEvict 注解的方法。
     * <p>
     * 在方法执行前后（根据配置）注册缓存驱逐调用信息，然后继续执行原方法。
     * </p>
     *
     * @param joinPoint 连接点
     * @param redisCacheEvict 注解实例
     * @return 原方法的返回值
     * @throws Throwable 如果原方法抛出异常
     */
    @Around("@annotation(redisCacheEvict)")
    public Object around(ProceedingJoinPoint joinPoint, RedisCacheEvict redisCacheEvict)
            throws Throwable {
        try {
            registerInvocation(joinPoint, redisCacheEvict);
        } catch (Exception e) {
            log.debug("Failed to register evict invocation: {}", e.getMessage());
        }
        return joinPoint.proceed();
    }

    /**
     * 注册驱逐调用信息。
     * <p>
     * 此方法不会改变 Spring Cache 的驱逐语义，仅用于记录与对称设计。
     * </p>
     *
     * @param joinPoint 连接点
     * @param redisCacheEvict 注解实例
     * @throws NoSuchMethodException 如果无法获取具体方法
     */
    private void registerInvocation(ProceedingJoinPoint joinPoint, RedisCacheEvict redisCacheEvict)
            throws NoSuchMethodException {

        Method method = getSpecificMethod(joinPoint);
        Object targetBean = joinPoint.getTarget();
        Object[] arguments = joinPoint.getArgs();
        String[] cacheNames = getCacheNames(redisCacheEvict);

        boolean allEntries = redisCacheEvict.allEntries();
        Object key = null;
        if (!allEntries) {
            key = resolveCacheKey(targetBean, method, arguments, redisCacheEvict);
        }

        EvictInvocation invocation =
                EvictInvocation.builder()
                        .arguments(arguments)
                        .targetBean(targetBean)
                        .targetMethod(method)
                        .cacheNames(cacheNames)
                        .key(key)
                        .allEntries(allEntries)
                        .beforeInvocation(redisCacheEvict.beforeInvocation())
                        .condition(nullToEmpty(redisCacheEvict.condition()))
                        .sync(redisCacheEvict.sync())
                        .build();

        for (String cacheName : cacheNames) {
            if (cacheName == null || cacheName.isBlank()) continue;
            registry.register(cacheName.trim(), key, invocation);
            log.debug(
                    "Registered EvictInvocation for cache={}, method={}, key={}, allEntries={}, beforeInvocation={}",
                    cacheName,
                    method.getName(),
                    key,
                    allEntries,
                    invocation.isBeforeInvocation());
        }
    }

    /**
     * 如果字符串为 null 则转换为空字符串。
     *
     * @param s 输入字符串
     * @return 非 null 的字符串
     */
    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 解析缓存 Key。
     * <p>
     * 使用统一的 KeyResolver 解析 key（SpEL -> KeyGenerator -> SimpleKey）。
     * </p>
     *
     * @param targetBean 目标 Bean
     * @param method 方法
     * @param arguments 方法参数
     * @param redisCacheEvict 注解实例
     * @return 解析后的缓存 Key
     */
    private Object resolveCacheKey(
            Object targetBean, Method method, Object[] arguments, RedisCacheEvict redisCacheEvict) {
        return KeyResolver.resolveKey(
                targetBean,
                method,
                arguments,
                redisCacheEvict.key(),
                redisCacheEvict.keyGenerator(),
                applicationContext,
                this.keyGenerator);
    }

    /**
     * 根据连接点获取具体方法。
     *
     * @param joinPoint 连接点
     * @return 具体方法
     * @throws NoSuchMethodException 如果方法不存在
     */
    private Method getSpecificMethod(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        Object target = joinPoint.getTarget();
        String methodName = joinPoint.getSignature().getName();
        Class<?>[] parameterTypes =
                ((MethodSignature) joinPoint.getSignature()).getMethod().getParameterTypes();
        return target.getClass().getMethod(methodName, parameterTypes);
    }

    /**
     * 获取缓存名数组（合并 value 与 cacheNames）。
     *
     * @param ann 注解实例
     * @return 缓存名数组
     */
    private String[] getCacheNames(RedisCacheEvict ann) {
        Set<String> list = new LinkedHashSet<>();
        for (String v : ann.value()) if (v != null && !v.isBlank()) list.add(v);
        for (String v : ann.cacheNames()) if (v != null && !v.isBlank()) list.add(v);
        return list.toArray(String[]::new);
    }
}
