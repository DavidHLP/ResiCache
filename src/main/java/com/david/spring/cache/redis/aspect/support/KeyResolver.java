package com.david.spring.cache.redis.aspect.support;

import com.david.spring.cache.redis.reflect.support.ContextBeanSupport;

import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

public final class KeyResolver {

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    // ======== 基于 KeyGenerator 的主 key 解析 ========

    private KeyResolver() {}

    public static Object resolveKey(
            Object targetBean, Method method, Object[] arguments, String keyGeneratorBeanName) {
        try {
            KeyGenerator generator =
                    ContextBeanSupport.resolveKeyGenerator(null, keyGeneratorBeanName);
            if (generator != null) {
                return generator.generate(targetBean, method, arguments);
            }
        } catch (Exception ignore) {
        }

        return SimpleKeyGenerator.generateKey(arguments);
    }

    // ======== 基于 SpEL 的多/单 key 与条件解析 ========

    public static String[] getCacheNames(String[] values, String[] cacheNames) {
        Set<String> list = new LinkedHashSet<>();
        for (String v : values) if (v != null && !v.isBlank()) list.add(v);
        for (String v : cacheNames) if (v != null && !v.isBlank()) list.add(v);
        return list.toArray(String[]::new);
    }

    /**
     * 解析多个 SpEL 表达式为一组 key，支持集合/数组展开。
     */
    public static Object[] resolveKeysSpEL(
            Object targetBean, Method method, Object[] arguments, String[] expressions) {
        if (expressions == null || expressions.length == 0) return new Object[0];
        StandardEvaluationContext ctx = buildContext(targetBean, method, arguments);
        Set<Object> keys = new LinkedHashSet<>();
        for (String expr : expressions) {
            if (expr == null || expr.isBlank()) continue;
            Object value;
            try {
                Expression e = PARSER.parseExpression(expr);
                value = e.getValue(ctx);
            } catch (Exception parseEx) {
                // 退化：无法解析为 SpEL 时，按字面量处理
                value = expr;
            }
            addTo(keys, value);
        }
        return keys.toArray(Object[]::new);
    }

    /**
     * 解析单个 SpEL 表达式为一个 key 值；当表达式为空返回 null；解析失败时按字面量返回。
     */
    public static Object resolveKeySpEL(
            Object targetBean, Method method, Object[] arguments, String expression) {
        if (expression == null || expression.isBlank()) return null;
        try {
            StandardEvaluationContext ctx = buildContext(targetBean, method, arguments);
            Expression e = PARSER.parseExpression(expression);
            return e.getValue(ctx);
        } catch (Exception ignore) {
            return expression;
        }
    }

    /**
     * 计算条件表达式，若表达式为空返回 true；解析异常或结果为 null/false 时返回 false。
     */
    public static boolean evaluateConditionSpEL(
            Object targetBean, Method method, Object[] arguments, String conditionExpr) {
        if (conditionExpr == null || conditionExpr.isBlank()) return true;
        try {
            StandardEvaluationContext ctx = buildContext(targetBean, method, arguments);
            Expression e = PARSER.parseExpression(conditionExpr);
            Boolean r = e.getValue(ctx, Boolean.class);
            return Boolean.TRUE.equals(r);
        } catch (Exception ignore) {
            return false;
        }
    }

    private static void addTo(Set<Object> out, Object value) {
        if (value == null) return;
        if (value instanceof Iterable<?> it) {
            for (Object o : it) if (o != null) out.add(o);
            return;
        }
        Class<?> c = value.getClass();
        if (c.isArray()) {
            int len = Array.getLength(value);
            for (int i = 0; i < len; i++) {
                Object o = Array.get(value, i);
                if (o != null) out.add(o);
            }
            return;
        }
        out.add(value);
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
}
