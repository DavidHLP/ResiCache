package com.david.spring.cache.redis.support;

import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;

/**
 * 统一的 Key 解析工具：优先 SpEL，其次（可选的）KeyGenerator Bean，最后回退 SimpleKeyGenerator。
 */
public final class KeyResolver {

    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAM_DISC = new DefaultParameterNameDiscoverer();

    private KeyResolver() {}

    public static Object resolveKey(
            Object targetBean,
            Method method,
            Object[] arguments,
            String keySpEL,
            String keyGeneratorBeanName,
            ApplicationContext applicationContext,
            KeyGenerator defaultKeyGenerator) {
        // 1) 优先 SpEL
        try {
            if (keySpEL != null && !keySpEL.isBlank()) {
                MethodBasedEvaluationContext context =
                        new MethodBasedEvaluationContext(targetBean, method, arguments, PARAM_DISC);
                context.setBeanResolver(new BeanFactoryResolver(applicationContext));
                Expression expression = EXPRESSION_PARSER.parseExpression(keySpEL);
                Object val = expression.getValue(context);
                if (val != null) {
                    return val;
                }
            }
        } catch (Exception ignore) {
        }

        // 2) 指定 KeyGenerator Bean
        try {
            KeyGenerator generator = defaultKeyGenerator;
            if (keyGeneratorBeanName != null && !keyGeneratorBeanName.isBlank()) {
                try {
                    generator = applicationContext.getBean(keyGeneratorBeanName, KeyGenerator.class);
                } catch (Exception ignore) {
                }
            }
            return generator.generate(targetBean, method, arguments);
        } catch (Exception ignore) {
        }

        // 3) 兜底 SimpleKey 语义
        return org.springframework.cache.interceptor.SimpleKeyGenerator.generateKey(arguments);
    }
}
