package com.david.spring.cache.redis.chain;

import com.david.spring.cache.redis.chain.context.CacheContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

/**
 * 缓存操作助手类
 * 提取公共的缓存操作逻辑，避免代码重复
 *
 * @author david
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheOperationHelper {

    private final CacheManager cacheManager;
    private final SpelExpressionParser parser = new SpelExpressionParser();

    /**
     * 获取缓存实例
     *
     * @param cacheName 缓存名称
     * @return 缓存实例，如果不存在则返回null
     */
    public Cache getCache(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            log.warn("未找到缓存: {}", cacheName);
        }
        return cache;
    }

    /**
     * 获取返回值
     * 统一处理缓存命中和未命中的情况
     *
     * @param context 缓存上下文
     * @return 最终返回值
     */
    public Object getReturnValue(CacheContext context) {
        if (context.isCacheHit()) {
            return context.getCacheValue();
        }
        return context.getResult();
    }

    /**
     * 执行原始方法
     * 统一的方法执行逻辑，包含异常处理
     *
     * @param context 缓存上下文
     * @return 方法执行结果
     * @throws Throwable 方法执行异常
     */
    public Object executeMethod(CacheContext context) throws Throwable {
        log.debug("执行原始方法: {}.{}",
                context.getTargetClass().getSimpleName(),
                context.getMethod().getName());

        try {
            Object result = context.getJoinPoint().proceed();
            context.setResult(result);
            log.debug("方法执行完成，结果: {}", result);
            return result;
        } catch (Throwable e) {
            log.error("方法执行失败: {}.{}",
                    context.getTargetClass().getSimpleName(),
                    context.getMethod().getName(), e);
            context.setException(e);
            throw e;
        }
    }

    /**
     * 检查SpEL条件表达式是否通过
     *
     * @param expression SpEL表达式
     * @param context 缓存上下文
     * @return 条件是否通过
     */
    public boolean evaluateCondition(String expression, CacheContext context) {
        if (!StringUtils.hasText(expression)) {
            return true;
        }

        try {
            Expression expr = parser.parseExpression(expression);
            EvaluationContext evaluationContext = createEvaluationContext(context);
            Boolean result = expr.getValue(evaluationContext, Boolean.class);
            return result == null || result;
        } catch (Exception e) {
            log.warn("SpEL表达式求值失败: {}", expression, e);
            return true; // 默认通过
        }
    }

    /**
     * 检查unless条件，决定是否可以写入缓存
     *
     * @param unless unless表达式
     * @param context 缓存上下文
     * @return 是否可以写入缓存
     */
    public boolean canPutToCache(String unless, CacheContext context) {
        if (!StringUtils.hasText(unless)) {
            return true;
        }

        try {
            Expression expression = parser.parseExpression(unless);
            EvaluationContext evaluationContext = createEvaluationContext(context);
            Boolean result = expression.getValue(evaluationContext, Boolean.class);
            return result == null || !result;
        } catch (Exception e) {
            log.warn("unless条件表达式求值失败: {}", unless, e);
            return true; // 默认允许缓存
        }
    }

    /**
     * 将值写入缓存
     *
     * @param context 缓存上下文
     * @param value 要缓存的值
     * @return 写入成功返回true
     */
    public boolean putToCache(CacheContext context, Object value) {
        try {
            Cache cache = getCache(context.getCacheName());
            if (cache == null) {
                log.warn("缓存不存在，跳过写入: {}", context.getCacheName());
                return false;
            }

            cache.put(context.getCacheKey(), value);
            log.debug("缓存写入成功: cacheName={}, key={}, value={}", 
                    context.getCacheName(), context.getCacheKey(), 
                    value != null ? "有数据" : "null");
            return true;

        } catch (Exception e) {
            log.error("缓存写入失败: cacheName={}, key={}, error={}", 
                    context.getCacheName(), context.getCacheKey(), e.getMessage());
            return false;
        }
    }

    /**
     * 创建SpEL表达式求值上下文
     */
    private EvaluationContext createEvaluationContext(CacheContext context) {
        StandardEvaluationContext evaluationContext = new StandardEvaluationContext();

        // 设置方法参数
        String[] paramNames = getParameterNames(context.getMethod());
        Object[] args = context.getArgs();

        for (int i = 0; i < paramNames.length && i < args.length; i++) {
            evaluationContext.setVariable(paramNames[i], args[i]);
        }

        // 设置方法结果
        evaluationContext.setVariable("result", context.getResult());

        // 设置目标对象
        evaluationContext.setRootObject(context.getTarget());

        return evaluationContext;
    }

    /**
     * 获取方法参数名称
     */
    private String[] getParameterNames(Method method) {
        java.lang.reflect.Parameter[] parameters = method.getParameters();
        String[] paramNames = new String[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            paramNames[i] = parameters[i].getName();
        }

        return paramNames;
    }
}
