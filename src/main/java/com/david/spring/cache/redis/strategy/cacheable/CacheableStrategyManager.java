package com.david.spring.cache.redis.strategy.cacheable;

import com.david.spring.cache.redis.reflect.CachedInvocation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 缓存策略管理器
 * 负责选择合适的策略来处理缓存操作
 *
 * @author David
 */
@Slf4j
@Component
public class CacheableStrategyManager {

    private final List<CacheableStrategy<?>> strategies;

    public CacheableStrategyManager(List<CacheableStrategy<?>> strategies) {
        this.strategies = strategies.stream()
                .sorted(Comparator.comparingInt(CacheableStrategy::getPriority))
                .toList();
        log.info("Initialized CacheableStrategyManager with {} strategies: {}",
                strategies.size(),
                strategies.stream().map(CacheableStrategy::getStrategyName).toList());
    }

    /**
     * 选择合适的策略处理缓存调用
     *
     * @param cachedInvocation 缓存调用信息
     * @return 合适的策略，如果没有找到则返回null
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> CacheableStrategy<T> selectStrategy(@NonNull CachedInvocation cachedInvocation) {
        Optional<CacheableStrategy<?>> strategy = strategies.stream()
                .filter(s -> s.supports(cachedInvocation))
                .findFirst();

        if (strategy.isPresent()) {
            log.debug("Selected strategy: {} for method: {}",
                    strategy.get().getStrategyName(),
                    cachedInvocation.getMethodSignature());
            return (CacheableStrategy<T>) strategy.get();
        }

        log.warn("No suitable strategy found for method: {}, available strategies: {}",
                cachedInvocation.getMethodSignature(),
                strategies.stream().map(CacheableStrategy::getStrategyName).toList());
        return null;
    }

    /**
     * 获取所有已注册的策略
     *
     * @return 策略列表
     */
    @NonNull
    public List<CacheableStrategy<?>> getAllStrategies() {
        return List.copyOf(strategies);
    }

    /**
     * 根据名称获取策略
     *
     * @param strategyName 策略名称
     * @return 策略实例，如果未找到则返回null
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> CacheableStrategy<T> getStrategyByName(@NonNull String strategyName) {
        return (CacheableStrategy<T>) strategies.stream()
                .filter(s -> s.getStrategyName().equals(strategyName))
                .findFirst()
                .orElse(null);
    }
}