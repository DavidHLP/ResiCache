package com.david.spring.cache.redis.core.strategy;

import com.david.spring.cache.redis.core.CacheOperationResolver;
import org.aspectj.lang.ProceedingJoinPoint;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 缓存执行策略接口
 * 使用策略模式来处理不同的缓存执行逻辑
 */
public interface CacheExecutionStrategy {

    /**
     * 执行缓存操作
     *
     * @param joinPoint 连接点
     * @param operations 缓存操作列表
     * @param method 方法
     * @param args 参数
     * @param targetClass 目标类
     * @return 执行结果
     * @throws Throwable 执行异常
     */
    Object execute(ProceedingJoinPoint joinPoint,
                   List<CacheOperationResolver.CacheableOperation> operations,
                   Method method,
                   Object[] args,
                   Class<?> targetClass) throws Throwable;

    /**
     * 判断该策略是否支持指定的操作
     *
     * @param operation 缓存操作
     * @return 是否支持
     */
    boolean supports(CacheOperationResolver.CacheableOperation operation);

    /**
     * 获取策略优先级，数值越小优先级越高
     *
     * @return 优先级
     */
    int getOrder();
}