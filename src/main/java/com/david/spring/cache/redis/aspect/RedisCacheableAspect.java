package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationContext;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RedisCacheableAspect {

    private final CacheInvocationRegistry registry;
    private final KeyGenerator keyGenerator;
    private final ApplicationContext applicationContext;
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer =
            new DefaultParameterNameDiscoverer();

    public RedisCacheableAspect(
            CacheInvocationRegistry registry,
            KeyGenerator keyGenerator,
            ApplicationContext applicationContext) {
        this.registry = registry;
        this.keyGenerator = keyGenerator;
        this.applicationContext = applicationContext;
    }

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

    /** 注册缓存调用信息 */
    private void registerInvocation(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable)
            throws NoSuchMethodException {

        Method method = getSpecificMethod(joinPoint);
        Object targetBean = joinPoint.getTarget();
        Object[] arguments = joinPoint.getArgs();
        String[] cacheNames = getCacheNames(redisCacheable);

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
     * 解析注解中的 SpEL key，或回退到（可能是自定义的）KeyGenerator。
     */
    private Object resolveCacheKey(
            Object targetBean, Method method, Object[] arguments, RedisCacheable redisCacheable) {
        // 1) 优先使用 SpEL key 表达式
        try {
            String keySpEL = redisCacheable.key();
            if (keySpEL != null && !keySpEL.isBlank()) {
                MethodBasedEvaluationContext context =
                        new MethodBasedEvaluationContext(
                                targetBean, method, arguments, parameterNameDiscoverer);
                context.setBeanResolver(new BeanFactoryResolver(applicationContext));
                Expression expression = expressionParser.parseExpression(keySpEL);
                Object val = expression.getValue(context);
                if (val != null) {
                    return val;
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to evaluate key SpEL: {}", ex.getMessage());
        }

        // 2) 未指定 SpEL 或解析失败时，使用 KeyGenerator（支持注解里指定的 keyGenerator 名称）
        try {
            KeyGenerator generator = this.keyGenerator;
            String keyGenName = redisCacheable.keyGenerator();
            if (keyGenName != null && !keyGenName.isBlank()) {
                try {
                    generator = applicationContext.getBean(keyGenName, KeyGenerator.class);
                } catch (Exception e) {
                    log.warn("Failed to get KeyGenerator bean '{}': {}", keyGenName, e.getMessage());
                }
            }
            return generator.generate(targetBean, method, arguments);
        } catch (Exception e) {
            log.warn("Failed to generate key via KeyGenerator: {}", e.getMessage());
            // 3) 最后兜底：使用 SimpleKey 语义
            return org.springframework.cache.interceptor.SimpleKeyGenerator.generateKey(arguments);
        }
    }

    /** 根据 JoinPoint 获取具体方法 */
    private Method getSpecificMethod(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        Object target = joinPoint.getTarget();
        String methodName = joinPoint.getSignature().getName();
        Class<?>[] parameterTypes =
                ((MethodSignature) joinPoint.getSignature()).getMethod().getParameterTypes();
        return target.getClass().getMethod(methodName, parameterTypes);
    }

    /** 获取缓存名数组（合并 value 与 cacheNames） */
    private String[] getCacheNames(RedisCacheable redisCacheable) {
        Set<String> list = new LinkedHashSet<>();
        for (String v : redisCacheable.value()) if (v != null && !v.isBlank()) list.add(v);
        for (String v : redisCacheable.cacheNames()) if (v != null && !v.isBlank()) list.add(v);
        return list.toArray(String[]::new);
    }
}
