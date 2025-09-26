package com.david.spring.cache.redis.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
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
		return evaluateExpression(conditionExpression, method, args, target, targetClass, result,
				this::getConditionExpression, Boolean.class, true, "condition");
	}

	public boolean evaluateUnless(String unlessExpression, Method method, Object[] args,
	                              Object target, Class<?> targetClass, Object result) {
		return evaluateExpression(unlessExpression, method, args, target, targetClass, result,
				this::getUnlessExpression, Boolean.class, false, "unless");
	}

	public Object generateKey(String keyExpression, Method method, Object[] args,
	                          Object target, Class<?> targetClass, Object result) {
		return evaluateExpression(keyExpression, method, args, target, targetClass, result,
				this::getKeyExpression, Object.class, null, "key expression");
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

	private <T> T evaluateExpression(String expressionStr, Method method, Object[] args,
	                                 Object target, Class<?> targetClass, Object result,
	                                 ExpressionGetter expressionGetter, Class<T> resultType,
	                                 T defaultValue, String expressionType) {
		if (!StringUtils.hasText(expressionStr)) {
			return defaultValue;
		}

		EvaluationContext evaluationContext = createEvaluationContext(method, args, target, targetClass, result);
		Expression expression = expressionGetter.get(expressionStr, method, targetClass);

		try {
			T evaluationResult = expression.getValue(evaluationContext, resultType);
			log.debug("{} '{}' evaluated to: {}", expressionType, expressionStr, evaluationResult);
			if (Boolean.class.equals(resultType)) return resultType.cast(Boolean.TRUE.equals(evaluationResult));
			return evaluationResult;
		} catch (Exception e) {
			log.warn("Failed to evaluate {} '{}': {}", expressionType, expressionStr, e.getMessage());
			return defaultValue;
		}
	}

	@FunctionalInterface
	private interface ExpressionGetter {
		Expression get(String expression, Method method, Class<?> targetClass);
	}

	private record ExpressionKey(String expression, Method method, Class<?> targetClass) {

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof ExpressionKey otherKey)) {
				return false;
			}
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

	private record CacheExpressionRootObject(Method method, Object[] args, Object target, Class<?> targetClass) {

	}
}