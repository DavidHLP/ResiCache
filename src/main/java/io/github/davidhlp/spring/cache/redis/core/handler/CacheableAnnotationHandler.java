package io.github.davidhlp.spring.cache.redis.core.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.core.factory.CacheableOperationFactory;
import io.github.davidhlp.spring.cache.redis.register.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Handler for @RedisCacheable annotations.
 *
 * <p>Registers cacheable operations with metadata lookup keys that match
 * Spring's actual cache key resolution as closely as possible.
 */
@Slf4j
@Component
public class CacheableAnnotationHandler extends AnnotationHandler {

    private final RedisCacheRegister redisCacheRegister;
    private final KeyGenerator keyGenerator;
    private final CacheableOperationFactory cacheableOperationFactory;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private boolean failOnSpelError = true;

    public CacheableAnnotationHandler(
            RedisCacheRegister redisCacheRegister,
            KeyGenerator keyGenerator,
            CacheableOperationFactory cacheableOperationFactory) {
        this.redisCacheRegister = redisCacheRegister;
        this.keyGenerator = keyGenerator;
        this.cacheableOperationFactory = cacheableOperationFactory;
    }

    public void setFailOnSpelError(boolean failOnSpelError) {
        this.failOnSpelError = failOnSpelError;
    }

    @Override
    protected boolean canHandle(Method method) {
        return method.isAnnotationPresent(RedisCacheable.class);
    }

    @Override
    protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
        RedisCacheable[] cacheables = method.getAnnotationsByType(RedisCacheable.class);
        List<CacheOperation> operations = new ArrayList<>();

        for (RedisCacheable cacheable : cacheables) {
            RedisCacheableOperation operation = registerCacheableOperation(method, target, args, cacheable);
            if (operation != null) {
                operations.add(operation);
            }
        }

        return operations;
    }

    private RedisCacheableOperation registerCacheableOperation(
            Method method, Object target, Object[] args, RedisCacheable redisCacheable) {
        try {
            String key = resolveKey(target, method, args, redisCacheable.key());
            RedisCacheableOperation operation =
                    cacheableOperationFactory.create(method, redisCacheable, target, args, key);

            redisCacheRegister.registerCacheableOperation(operation);
            log.debug(
                    "Registered cacheable operation: {} with key: {} for caches: {}",
                    method.getName(),
                    key,
                    String.join(",", operation.getCacheNames()));
            return operation;
        } catch (Exception e) {
            log.error("Failed to register cacheable operation", e);
            return null;
        }
    }

    /**
     * Resolves the cache key using the same logic Spring Cache uses:
     * if a SpEL key expression is provided, evaluate it;
     * otherwise fall back to the configured KeyGenerator.
     */
    private String resolveKey(Object target, Method method, Object[] args, String keyExpression) {
        if (StringUtils.hasText(keyExpression)) {
            try {
                StandardEvaluationContext context = new StandardEvaluationContext();
                context.setVariable("root", new RootObject(method, args, target));
                // Bind method parameters as variables (e.g., #id, #name)
                bindMethodParameters(context, method, args);
                Object key = parser.parseExpression(keyExpression).getValue(context);
                if (key != null) {
                    return key.toString();
                }
            } catch (Exception e) {
                if (failOnSpelError) {
                    throw new IllegalStateException(
                            "Failed to evaluate SpEL key expression '" + keyExpression + "'", e);
                }
                log.warn("Failed to evaluate SpEL key expression '{}', falling back to KeyGenerator", keyExpression);
            }
        }
        Object key = keyGenerator.generate(target, method, args);
        return String.valueOf(key);
    }

    private void bindMethodParameters(StandardEvaluationContext context, Method method, Object[] args) {
        java.lang.reflect.Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length && i < args.length; i++) {
            String paramName = params[i].getName();
            if (!paramName.equals("arg" + i)) {
                context.setVariable(paramName, args[i]);
            }
            context.setVariable("a" + i, args[i]);
            context.setVariable("p" + i, args[i]);
        }
    }

    private static class RootObject {
        private final Method method;
        private final Object[] args;
        private final Object target;

        RootObject(Method method, Object[] args, Object target) {
            this.method = method;
            this.args = args;
            this.target = target;
        }

        public Method getMethod() { return method; }
        public Object[] getArgs() { return args; }
        public Object getTarget() { return target; }
        public Class<?> getTargetClass() { return target != null ? target.getClass() : null; }
        public String getMethodName() { return method != null ? method.getName() : null; }
    }
}
