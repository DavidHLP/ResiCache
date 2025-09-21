package com.david.spring.cache.redis.aspect.support;

import com.david.spring.cache.redis.reflect.support.SpringContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
public final class KeyResolver {

	private static final ExpressionParser PARSER = new SpelExpressionParser();


	private KeyResolver() {}

	public static Object resolveKey(
			Object targetBean, Method method, Object[] arguments, String keyGeneratorBeanName) {
		if (targetBean == null) {
			throw new IllegalArgumentException("Target bean cannot be null");
		}
		if (method == null) {
			throw new IllegalArgumentException("Method cannot be null");
		}
		if (arguments == null) {
			arguments = new Object[0]; // 使用空数组替代null
		}

		try {
			KeyGenerator generator = resolveKeyGenerator(keyGeneratorBeanName);
			if (generator != null) {
				return generator.generate(targetBean, method, arguments);
			}
		} catch (Exception e) {
			log.debug("Failed to resolve key generator '{}' for method {}, falling back to SimpleKeyGenerator: {}",
					keyGeneratorBeanName, method.getName(), e.getMessage());
		}

		return SimpleKeyGenerator.generateKey(arguments);
	}


	public static String[] getCacheNames(String[] values, String[] cacheNames) {
		Set<String> list = new LinkedHashSet<>();

		if (values != null) {
			for (String v : values) {
				if (v != null && !v.isBlank()) {
					list.add(v.trim());
				}
			}
		}

		if (cacheNames != null) {
			for (String v : cacheNames) {
				if (v != null && !v.isBlank()) {
					list.add(v.trim());
				}
			}
		}

		return list.toArray(String[]::new);
	}

	/**
	 * 解析单个 SpEL 表达式为一个 key 值；当表达式为空返回 null；解析失败时按字面量返回。
	 */
	public static Object resolveKeySpEL(
			Object targetBean, Method method, Object[] arguments, String expression) {
		if (expression == null || expression.isBlank()) {
			return null;
		}

		if (targetBean == null) {
			throw new IllegalArgumentException("Target bean cannot be null for SpEL evaluation");
		}
		if (method == null) {
			throw new IllegalArgumentException("Method cannot be null for SpEL evaluation");
		}
		if (arguments == null) {
			arguments = new Object[0]; // 使用空数组替代null
		}

		try {
			StandardEvaluationContext ctx = buildContext(targetBean, method, arguments);
			Expression e = PARSER.parseExpression(expression);
			return e.getValue(ctx);
		} catch (Exception e) {
			log.debug("Failed to evaluate SpEL expression '{}' for method {}, using literal value: {}",
					expression, method.getName(), e.getMessage());
			return expression;
		}
	}

	private static StandardEvaluationContext buildContext(
			Object targetBean, Method method, Object[] arguments) {
		StandardEvaluationContext ctx = new StandardEvaluationContext(targetBean);
		ctx.setVariable("target", targetBean);
		ctx.setVariable("method", method);
		ctx.setVariable("args", arguments);
		for (int i = 0; i < arguments.length; i++) {
			ctx.setVariable("p" + i, arguments[i]);
			ctx.setVariable("a" + i, arguments[i]);
		}
		DefaultParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();
		String[] names = discoverer.getParameterNames(method);
		if (names != null) {
			for (int i = 0; i < names.length && i < arguments.length; i++) {
				String name = names[i];
				if (name != null && !name.isBlank()) {
					ctx.setVariable(name, arguments[i]);
				}
			}
		}
		return ctx;
	}

	/**
	 * 解析KeyGenerator Bean，优先按名称解析，失败则按类型解析
	 *
	 * @param name KeyGenerator Bean名称
	 * @return KeyGenerator实例，如果解析失败返回null
	 */
	private static KeyGenerator resolveKeyGenerator(String name) {
		log.debug("Resolving KeyGenerator with name: {}", name);
		if (name != null && !name.isBlank()) {
			KeyGenerator bean = SpringContextHolder.getBean(name, KeyGenerator.class);
			if (bean != null) {
				log.debug("Successfully resolved KeyGenerator by name: {}", name);
				return bean;
			} else {
				log.debug("KeyGenerator not found by name: {}, trying by type", name);
			}
		}

		KeyGenerator byType = SpringContextHolder.getBean(KeyGenerator.class);
		if (byType != null) {
			log.debug("Successfully resolved KeyGenerator by type");
			return byType;
		} else {
			log.warn("No KeyGenerator found by name or type");
		}
		return null;
	}
}
