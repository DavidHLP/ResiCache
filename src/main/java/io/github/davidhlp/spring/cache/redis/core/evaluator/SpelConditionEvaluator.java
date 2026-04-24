package io.github.davidhlp.spring.cache.redis.core.evaluator;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheableOperation;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SpEL 条件表达式求值器 用于对 @RedisCacheable、@RedisCachePut 等注解的 condition 和 unless
 * 属性进行 SpEL 求值。
 *
 * <p>condition 在方法执行前求值，为 false 时跳过整个缓存操作。
 * <p>unless 在方法执行后求值，为 true 时跳过缓存（不缓存结果）。
 */
@Slf4j
public class SpelConditionEvaluator {

    private static final SpelConditionEvaluator INSTANCE = new SpelConditionEvaluator();

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    private SpelConditionEvaluator() {}

    public static SpelConditionEvaluator getInstance() {
        return INSTANCE;
    }

    /**
     * 判断缓存操作是否应该执行（基于 condition 表达式）
     *
     * @param operation 缓存操作
     * @param method 被调用的方法
     * @param args 方法参数
     * @param target 目标对象
     * @return true 表示应该执行缓存操作，false 表示跳过
     */
    public boolean shouldProceed(
            CacheOperation operation,
            Method method,
            Object[] args,
            Object target) {
        String condition = operation.getCondition();
        if (!StringUtils.hasText(condition)) {
            return true;
        }

        EvaluationContext context = createEvaluationContext(method, args, target, null);
        return evaluateCondition(condition, context);
    }

    /**
     * 判断方法结果是否应该被缓存（基于 unless 表达式）
     *
     * @param operation 缓存操作（必须是 CacheableOperation）
     * @param method 被调用的方法
     * @param args 方法参数
     * @param target 目标对象
     * @param result 方法执行结果
     * @return true 表示不缓存结果，false 表示缓存结果
     */
    public boolean shouldSkipCache(
            CacheOperation operation,
            Method method,
            Object[] args,
            Object target,
            @Nullable Object result) {
        String unless = getUnlessFromOperation(operation);
        if (!StringUtils.hasText(unless)) {
            return false;
        }

        EvaluationContext context = createEvaluationContext(method, args, target, result);
        return evaluateUnless(unless, context);
    }

    /**
     * 从操作对象中获取 unless 表达式
     * 支持 RedisCacheableOperation、RedisCachePutOperation 和 Spring CacheableOperation
     */
    private String getUnlessFromOperation(CacheOperation operation) {
        // 直接访问 unless 字段，绕过 Spring 的类型检查
        try {
            java.lang.reflect.Field unlessField = operation.getClass().getDeclaredField("unless");
            unlessField.setAccessible(true);
            Object value = unlessField.get(operation);
            return value != null ? value.toString() : "";
        } catch (Exception e) {
            log.warn("Failed to access 'unless' field via reflection for {}. Using empty condition. Error: {}",
                    operation.getClass().getName(), e.getMessage());
            return "";
        }
    }

    /**
     * 创建 SpEL 求值上下文
     *
     * @param method 方法
     * @param args 参数
     * @param target 目标对象
     * @param result 结果（可为 null）
     * @return 求值上下文
     */
    private EvaluationContext createEvaluationContext(
            Method method, Object[] args, Object target, @Nullable Object result) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setRootObject(new RootObject(method, args, target, result));
        context.addPropertyAccessor(new MapAccessor());
        return context;
    }

    /**
     * 求值 condition 表达式
     *
     * @param expression SpEL 表达式字符串
     * @param context 求值上下文
     * @return 求值结果
     */
    private boolean evaluateCondition(String expression, EvaluationContext context) {
        try {
            Expression exp = getExpression(expression);
            Boolean result = exp.getValue(context, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            // 求值失败时，默认为继续执行（不跳过）
            return true;
        }
    }

    /**
     * 求值 unless 表达式
     *
     * @param expression SpEL 表达式字符串
     * @param context 求值上下文
     * @return true 表示结果应被排除（不缓存）
     */
    private boolean evaluateUnless(String expression, EvaluationContext context) {
        try {
            Expression exp = getExpression(expression);
            Boolean result = exp.getValue(context, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            // 求值失败时，默认为不排除（缓存）
            return false;
        }
    }

    /**
     * 获取缓存的表达式对象
     *
     * @param expressionString 表达式字符串
     * @return 编译后的表达式
     */
    private Expression getExpression(String expressionString) {
        return expressionCache.computeIfAbsent(
                expressionString, key -> parser.parseExpression(key));
    }

    /**
     * 根对象，包含方法调用上下文信息
     */
    private static class RootObject {
        private final Method method;
        private final Object[] args;
        private final Object target;
        @Nullable private final Object result;

        // SpEL 变量
        private final Map<String, Object> variables = new HashMap<>();

        RootObject(Method method, Object[] args, Object target, @Nullable Object result) {
            this.method = method;
            this.args = args;
            this.target = target;
            this.result = result;
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

        @Nullable
        public Object getResult() {
            return result;
        }
    }
}
