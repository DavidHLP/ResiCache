package io.github.davidhlp.spring.cache.redis.evaluator;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * SpEL 条件表达式求值器 用于对 @RedisCacheable、@RedisCachePut 等注解的 condition 和 unless
 * 属性进行 SpEL 求值。
 *
 * <p>condition 在方法执行前求值，为 false 时跳过整个缓存操作。
 * <p>unless 在方法执行后求值，为 true 时跳过缓存（不缓存结果）。
 *
 * <p><b>安全须知（重要）</b>：本求值器使用 {@link StandardEvaluationContext}，具备完整的 SpEL
 * 能力（包括类型引用 {@code T(...)}、任意方法调用等）。因此 condition/unless 表达式
 * <b>必须来自可信源</b>——即注解中由开发者在源码内写死的字面量，仅在注解元数据层面流转，
 * <b>严禁</b>直接拼接或求值终端用户的运行时输入，否则可能导致任意代码执行（RCE）。本实现与
 * Spring 原生 {@code @Cacheable} 的 condition/unless 处理行为一致。若未来需要求值动态生成的
 * 表达式，必须改用受限的 {@code SimpleEvaluationContext}（仅支持属性读写，禁止类型引用与方法调用）。
 */
@Slf4j
public class SpelConditionEvaluator {

    /** 最大表达式缓存数量，防止内存泄漏 */
    private static final int MAX_EXPRESSION_CACHE_SIZE = 1000;

    private static final SpelConditionEvaluator INSTANCE = new SpelConditionEvaluator();

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final Cache<String, Expression> expressionCache = Caffeine.newBuilder()
            .maximumSize(MAX_EXPRESSION_CACHE_SIZE)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    /** SpEL 求值失败时是否抛出异常（默认 true）。配置错误（语法错误）始终抛出。 */
    private volatile boolean failOnSpelError = true;

    private SpelConditionEvaluator() {}

    public static SpelConditionEvaluator getInstance() {
        return INSTANCE;
    }

    /**
     * 设置 SpEL 求值失败时的行为。
     *
     * @param failOnSpelError true 表示运行时求值失败时抛出异常；
     *                        false 表示运行时求值失败时返回默认值（不抛出）。
     *                        注意：SpEL 语法错误（配置错误）始终会抛出异常，不受此设置影响。
     */
    public void setFailOnSpelError(boolean failOnSpelError) {
        this.failOnSpelError = failOnSpelError;
    }

    /**
     * 获取当前 SpEL 求值失败时的行为设置。
     */
    public boolean isFailOnSpelError() {
        return failOnSpelError;
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
     * @param operation 缓存操作
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
     * 支持 RedisCacheableOperation 和 RedisCachePutOperation（直接字段访问）
     */
    private String getUnlessFromOperation(CacheOperation operation) {
        if (operation instanceof io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation cacheableOp) {
            return cacheableOp.getUnless() != null ? cacheableOp.getUnless() : "";
        }
        if (operation instanceof io.github.davidhlp.spring.cache.redis.operation.RedisCachePutOperation putOp) {
            return putOp.getUnless() != null ? putOp.getUnless() : "";
        }
        // Fallback for other CacheOperation types - return empty (no unless)
        return "";
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
     * @throws ParseException SpEL 语法错误（配置错误），始终抛出
     * @throws EvaluationException 运行时求值失败且 failOnSpelError=true 时抛出
     */
    private boolean evaluateCondition(String expression, EvaluationContext context) {
        try {
            Expression exp = getExpression(expression);
            Boolean result = exp.getValue(context, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (ParseException e) {
            // 配置错误（SpEL 语法错误）始终抛出，帮助开发者及时发现配置问题
            log.error("SpEL syntax error in condition expression (configuration error): expression={}", expression, e);
            throw e;
        } catch (EvaluationException e) {
            // 运行时错误（如 null 值、属性不存在）根据 failOnSpelError 决定行为
            if (failOnSpelError) {
                log.error("SpEL condition evaluation failed (runtime error): expression={}", expression, e);
                throw e;
            }
            // failOnSpelError=false 时，记录警告并返回安全默认值
            log.warn("SpEL condition evaluation failed, proceeding with cache: expression={}, error={}",
                    expression, e.getMessage());
            log.debug("SpEL condition evaluation stack trace", e);
            return true;
        }
    }

    /**
     * 求值 unless 表达式
     *
     * @param expression SpEL 表达式字符串
     * @param context 求值上下文
     * @return true 表示结果应被排除（不缓存）
     * @throws ParseException SpEL 语法错误（配置错误），始终抛出
     * @throws EvaluationException 运行时求值失败且 failOnSpelError=true 时抛出
     */
    private boolean evaluateUnless(String expression, EvaluationContext context) {
        try {
            Expression exp = getExpression(expression);
            Boolean result = exp.getValue(context, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (ParseException e) {
            // 配置错误（SpEL 语法错误）始终抛出，帮助开发者及时发现配置问题
            log.error("SpEL syntax error in unless expression (configuration error): expression={}", expression, e);
            throw e;
        } catch (EvaluationException e) {
            // 运行时错误（如 null 值、属性不存在）根据 failOnSpelError 决定行为
            if (failOnSpelError) {
                log.error("SpEL unless evaluation failed (runtime error): expression={}", expression, e);
                throw e;
            }
            // failOnSpelError=false 时，记录警告并返回安全默认值
            log.warn("SpEL unless evaluation failed, caching result: expression={}, error={}",
                    expression, e.getMessage());
            log.debug("SpEL unless evaluation stack trace", e);
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
        return expressionCache.get(expressionString, key -> parser.parseExpression(key));
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
