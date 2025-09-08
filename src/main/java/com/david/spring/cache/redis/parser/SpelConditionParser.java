package com.david.spring.cache.redis.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * SpEL条件表达式解析器
 * 用于解析和求值缓存条件表达式
 *
 * @author david
 */
@Slf4j
@Component
public class SpelConditionParser {

    private final SpelExpressionParser parser = new SpelExpressionParser();

    /**
     * 检查条件表达式是否通过
     *
     * @param condition 条件表达式
     * @param target    目标对象
     * @param method    方法
     * @param args      方法参数
     * @return 条件是否通过
     */
    public boolean isConditionPassing(String condition, Object target, Method method, Object[] args) {
        if (!StringUtils.hasText(condition)) {
            return true;
        }

        try {
            Expression expression = parser.parseExpression(condition);
            EvaluationContext evaluationContext = createEvaluationContext(target, method, args);
            Boolean result = expression.getValue(evaluationContext, Boolean.class);
            return result != null && result;
        } catch (Exception e) {
            log.warn("缓存条件表达式求值失败: {}", condition, e);
            return true; // 默认通过
        }
    }

    /**
     * 求值SpEL表达式
     *
     * @param expression SpEL表达式
     * @param target     目标对象
     * @param method     方法
     * @param args       方法参数
     * @param returnType 返回类型
     * @return 求值结果
     */
    public <T> T evaluateExpression(String expression, Object target, Method method, Object[] args, Class<T> returnType) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }

        try {
            Expression spelExpression = parser.parseExpression(expression);
            EvaluationContext evaluationContext = createEvaluationContext(target, method, args);
            return spelExpression.getValue(evaluationContext, returnType);
        } catch (Exception e) {
            log.warn("SpEL表达式求值失败: {}", expression, e);
            return null;
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
    private String[] getParameterNames(Method method) {
        Parameter[] parameters = method.getParameters();
        String[] paramNames = new String[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            paramNames[i] = parameters[i].getName();
        }

        return paramNames;
    }
}
