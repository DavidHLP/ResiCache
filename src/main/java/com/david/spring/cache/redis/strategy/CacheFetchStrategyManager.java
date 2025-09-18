package com.david.spring.cache.redis.strategy;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * 缓存获取策略管理器
 * 负责选择和执行合适的缓存获取策略
 */
@Slf4j
@Component
public class CacheFetchStrategyManager {

    @Autowired
    private List<CacheFetchStrategy> strategies = new ArrayList<>();

    private List<CacheFetchStrategy> sortedStrategies;

    @PostConstruct
    public void init() {
        // 按优先级排序策略
        sortedStrategies = new ArrayList<>(strategies);
        sortedStrategies.sort(Comparator.comparingInt(CacheFetchStrategy::getOrder));
        log.info("Initialized {} cache fetch strategies", sortedStrategies.size());
    }

    /**
     * 执行缓存获取策略链
     */
    public ValueWrapper fetch(CacheFetchStrategy.CacheFetchContext context) {
        if (context.invocationContext() == null) {
            // 如果没有调用上下文，使用简单策略
            return context.valueWrapper();
        }

        // 获取适用的策略列表
        List<CacheFetchStrategy> applicableStrategies = getApplicableStrategies(context.invocationContext());

        if (applicableStrategies.isEmpty()) {
            log.debug("No applicable strategies found, returning value as-is");
            return context.valueWrapper();
        }

        // 依次执行策略链
        ValueWrapper result = context.valueWrapper();
        for (CacheFetchStrategy strategy : applicableStrategies) {
            try {
                // 创建新的上下文，传递中间结果
                CacheFetchStrategy.CacheFetchContext updatedContext =
                    new CacheFetchStrategy.CacheFetchContext(
                        context.cacheName(),
                        context.key(),
                        context.cacheKey(),
                        result,
                        context.invocation(),
                        context.invocationContext(),
                        context.redisTemplate(),
                        context.callback()
                    );

                result = strategy.fetch(updatedContext);

                // 如果某个策略返回null，停止执行后续策略
                if (result == null && shouldStopOnNull(strategy)) {
                    break;
                }
            } catch (Exception e) {
                log.error("Error executing strategy {}: {}",
                    strategy.getClass().getSimpleName(), e.getMessage(), e);
            }
        }

        return result;
    }

    /**
     * 获取适用的策略列表
     */
    private List<CacheFetchStrategy> getApplicableStrategies(CachedInvocationContext context) {
        List<CacheFetchStrategy> applicable = new ArrayList<>();

        for (CacheFetchStrategy strategy : sortedStrategies) {
            if (strategy.supports(context)) {
                applicable.add(strategy);
                log.debug("Strategy {} is applicable", strategy.getClass().getSimpleName());
            }
        }

        // 如果没有找到任何适用的策略，使用默认策略
        if (applicable.isEmpty()) {
            for (CacheFetchStrategy strategy : sortedStrategies) {
                if (strategy instanceof SimpleFetchStrategy) {
                    applicable.add(strategy);
                    break;
                }
            }
        }

        return applicable;
    }

    /**
     * 判断是否应该在返回null时停止执行后续策略
     */
    private boolean shouldStopOnNull(CacheFetchStrategy strategy) {
        // 布隆过滤器策略返回null时应该停止
        return strategy instanceof BloomFilterStrategy;
    }

    /**
     * 添加策略（用于动态注册）
     */
    public void addStrategy(CacheFetchStrategy strategy) {
        strategies.add(strategy);
        init(); // 重新初始化
    }

    /**
     * 获取所有已注册的策略
     */
    public List<CacheFetchStrategy> getAllStrategies() {
        return Collections.unmodifiableList(sortedStrategies);
    }
}