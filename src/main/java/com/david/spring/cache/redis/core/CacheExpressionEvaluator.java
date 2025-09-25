package com.david.spring.cache.redis.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.*;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class CacheExpressionEvaluator {

    private final Map<ExpressionKey, Expression> conditionCache = new ConcurrentHashMap<>(64);
    private final Map<ExpressionKey, Expression> unlessCache = new ConcurrentHashMap<>(64);
    private final Map<ExpressionKey, Expression> keyCache = new ConcurrentHashMap<>(64);
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public boolean evaluateCondition(String conditionExpression, Method method, Object[] args,
                                   Object target, Class<?> targetClass, Object result) {
        if (!StringUtils.hasText(conditionExpression)) {
            return true;
        }

        EvaluationContext evaluationContext = createEvaluationContext(method, args, target, targetClass, result);
        Expression expression = getConditionExpression(conditionExpression, method, targetClass);

        try {
            Boolean conditionResult = expression.getValue(evaluationContext, Boolean.class);
            log.debug("Condition '{}' evaluated to: {}", conditionExpression, conditionResult);
            return Boolean.TRUE.equals(conditionResult);
        } catch (Exception e) {
            log.warn("Failed to evaluate condition '{}': {}", conditionExpression, e.getMessage());
            return false;
        }
    }

    public boolean evaluateUnless(String unlessExpression, Method method, Object[] args,
                                Object target, Class<?> targetClass, Object result) {
        if (!StringUtils.hasText(unlessExpression)) {
            return false;
        }

        EvaluationContext evaluationContext = createEvaluationContext(method, args, target, targetClass, result);
        Expression expression = getUnlessExpression(unlessExpression, method, targetClass);

        try {
            Boolean unlessResult = expression.getValue(evaluationContext, Boolean.class);
            log.debug("Unless '{}' evaluated to: {}", unlessExpression, unlessResult);
            return Boolean.TRUE.equals(unlessResult);
        } catch (Exception e) {
            log.warn("Failed to evaluate unless '{}': {}", unlessExpression, e.getMessage());
            return false;
        }
    }

    public Object generateKey(String keyExpression, Method method, Object[] args,
                            Object target, Class<?> targetClass, Object result) {
        if (!StringUtils.hasText(keyExpression)) {
            return null;
        }

        EvaluationContext evaluationContext = createEvaluationContext(method, args, target, targetClass, result);
        Expression expression = getKeyExpression(keyExpression, method, targetClass);

        try {
            Object key = expression.getValue(evaluationContext);
            log.debug("Key expression '{}' evaluated to: {}", keyExpression, key);
            return key;
        } catch (Exception e) {
            log.warn("Failed to evaluate key expression '{}': {}", keyExpression, e.getMessage());
            return null;
        }
    }

    private Expression getConditionExpression(String expression, Method method, Class<?> targetClass) {
        ExpressionKey key = new ExpressionKey(expression, method, targetClass);
        return conditionCache.computeIfAbsent(key, k -> parseExpression(expression));
    }

    private Expression getUnlessExpression(String expression, Method method, Class<?> targetClass) {
        ExpressionKey key = new ExpressionKey(expression, method, targetClass);
        return unlessCache.computeIfAbsent(key, k -> parseExpression(expression));
    }

    private Expression getKeyExpression(String expression, Method method, Class<?> targetClass) {
        ExpressionKey key = new ExpressionKey(expression, method, targetClass);
        return keyCache.computeIfAbsent(key, k -> parseExpression(expression));
    }

    private Expression parseExpression(String expression) {
        return parser.parseExpression(expression);
    }

    private EvaluationContext createEvaluationContext(Method method, Object[] args,
                                                    Object target, Class<?> targetClass, Object result) {
        CacheExpressionRootObject rootObject = new CacheExpressionRootObject(method, args, target, targetClass);
        MethodBasedEvaluationContext evaluationContext = new MethodBasedEvaluationContext(
                rootObject, method, args, parameterNameDiscoverer);

        if (result != null) {
            evaluationContext.setVariable("result", result);
        }

        return evaluationContext;
    }

    private static class ExpressionKey {
        private final String expression;
        private final Method method;
        private final Class<?> targetClass;

        public ExpressionKey(String expression, Method method, Class<?> targetClass) {
            this.expression = expression;
            this.method = method;
            this.targetClass = targetClass;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ExpressionKey)) {
                return false;
            }
            ExpressionKey otherKey = (ExpressionKey) other;
            return ObjectUtils.nullSafeEquals(this.expression, otherKey.expression) &&
                   ObjectUtils.nullSafeEquals(this.method, otherKey.method) &&
                   ObjectUtils.nullSafeEquals(this.targetClass, otherKey.targetClass);
        }

        @Override
        public int hashCode() {
            return ObjectUtils.nullSafeHashCode(this.expression) * 29 +
                   ObjectUtils.nullSafeHashCode(this.method) * 17 +
                   ObjectUtils.nullSafeHashCode(this.targetClass);
        }
    }

    private static class CacheExpressionRootObject {
        private final Method method;
        private final Object[] args;
        private final Object target;
        private final Class<?> targetClass;

        public CacheExpressionRootObject(Method method, Object[] args, Object target, Class<?> targetClass) {
            this.method = method;
            this.args = args;
            this.target = target;
            this.targetClass = targetClass;
        }

        public Method getMethod() {
            return method;
        }

        public Object[] getArgs() {
            return args;
        }

        public Object getTarget() {
            return target;
        }

        public Class<?> getTargetClass() {
            return targetClass;
        }
    }
}