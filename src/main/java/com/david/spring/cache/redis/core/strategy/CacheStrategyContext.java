package com.david.spring.cache.redis.core.strategy;

import com.david.spring.cache.redis.core.CacheOperationResolver;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 缓存策略上下文
 * 负责选择和执行合适的缓存策略
 */
@Slf4j
@Component
public class CacheStrategyContext {

    private final List<CacheExecutionStrategy> strategies;

    public CacheStrategyContext(List<CacheExecutionStrategy> strategies) {
        this.strategies = strategies.stream()
                .sorted((s1, s2) -> Integer.compare(s1.getOrder(), s2.getOrder()))
                .collect(Collectors.toList());
        log.info("Initialized CacheStrategyContext with {} strategies", this.strategies.size());
    }

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
    public Object execute(ProceedingJoinPoint joinPoint,
                         List<CacheOperationResolver.CacheableOperation> operations,
                         Method method,
                         Object[] args,
                         Class<?> targetClass) throws Throwable {

        if (operations.isEmpty()) {
            return joinPoint.proceed();
        }

        // 选择合适的策略
        CacheOperationResolver.CacheableOperation primaryOperation = operations.get(0);
        CacheExecutionStrategy selectedStrategy = selectStrategy(primaryOperation);

        log.debug("Selected strategy: {} for operation: {}",
                selectedStrategy.getClass().getSimpleName(), primaryOperation.getCacheNames()[0]);

        return selectedStrategy.execute(joinPoint, operations, method, args, targetClass);
    }

    /**
     * 根据缓存操作选择合适的策略
     *
     * @param operation 缓存操作
     * @return 选择的策略
     */
    private CacheExecutionStrategy selectStrategy(CacheOperationResolver.CacheableOperation operation) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(operation))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No suitable cache strategy found"));
    }
}