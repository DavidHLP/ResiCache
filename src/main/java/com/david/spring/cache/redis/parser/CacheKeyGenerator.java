package com.david.spring.cache.redis.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * 缓存键生成器
 * 支持SpEL表达式和默认键生成
 *
 * @author david
 */
@Slf4j
@Component
public class CacheKeyGenerator {

    private final KeyGenerator keyGenerator = new SimpleKeyGenerator();
    private final SpelExpressionParser parser = new SpelExpressionParser();

    /**
     * 生成缓存键
     *
     * @param keyExpression SpEL表达式
     * @param target        目标对象
     * @param method        方法
     * @param args          方法参数
     * @return 缓存键
     */
    public String generateCacheKey(String keyExpression, Object target, Method method, Object[] args) {
        if (StringUtils.hasText(keyExpression)) {
            // 使用SpEL表达式生成键
            return generateKeyFromExpression(keyExpression, target, method, args);
        } else {
            // 使用默认键生成器
            Object key = keyGenerator.generate(target, method, args);
            return key.toString();
        }
    }

    /**
     * 从SpEL表达式生成键
     *
     * @param keyExpression SpEL表达式
     * @param target        目标对象
     * @param method        方法
     * @param args          方法参数
     * @return 生成的键
     */
    public String generateKeyFromExpression(String keyExpression, Object target, Method method, Object[] args) {
        try {
            Expression expression = parser.parseExpression(keyExpression);
            EvaluationContext evaluationContext = createEvaluationContext(target, method, args);
            Object key = expression.getValue(evaluationContext);
            return key != null ? key.toString() : "";
        } catch (Exception e) {
            log.warn("SpEL表达式解析失败，使用默认键生成器: {}", keyExpression, e);
            Object key = keyGenerator.generate(target, method, args);
            return key.toString();
        }
    }

    /**
     * 创建SpEL表达式求值上下文
     *
     * @param target 目标对象
     * @param method 方法
     * @param args   方法参数
     * @return 求值上下文
     */
    public EvaluationContext createEvaluationContext(Object target, Method method, Object[] args) {
        StandardEvaluationContext evaluationContext = new StandardEvaluationContext();

        // 设置方法参数
        String[] paramNames = getParameterNames(method);

        for (int i = 0; i < paramNames.length && i < args.length; i++) {
            evaluationContext.setVariable(paramNames[i], args[i]);
        }

        // 设置目标对象
        evaluationContext.setRootObject(target);

        return evaluationContext;
    }

    /**
     * 获取方法参数名称
     *
     * @param method 方法
     * @return 参数名称数组
     */
    public String[] getParameterNames(Method method) {
        Parameter[] parameters = method.getParameters();
        String[] paramNames = new String[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            paramNames[i] = parameters[i].getName();
        }

        return paramNames;
    }
}
