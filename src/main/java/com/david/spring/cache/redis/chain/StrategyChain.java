package com.david.spring.cache.redis.chain;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache.ValueWrapper;

import java.util.List;

/**
 * 策略链执行器。
 * <p>
 * 简单优雅的策略链实现，按顺序执行策略直到获得结果或所有策略执行完毕。
 * </p>
 *
 * @author CacheGuard
 * @since 1.0.0
 */
@Slf4j
@Builder
public class StrategyChain {

    /** 策略列表 */
    private final List<CacheFetchStrategy> strategies;

    /** 是否在第一个有效结果后停止 */
    @Builder.Default
    private final boolean stopOnFirst = false;

    /** 是否在异常时继续执行 */
    @Builder.Default
    private final boolean continueOnException = true;

    /**
     * 执行策略链。
     *
     * @param context 策略执行上下文
     * @return 策略执行结果
     */
    @Nullable
    public ValueWrapper execute(@Nonnull CacheFetchStrategy.CacheFetchContext context) {
        if (strategies == null || strategies.isEmpty()) {
            log.debug("No strategies to execute");
            return context.valueWrapper();
        }

        ValueWrapper result = context.valueWrapper();

        for (CacheFetchStrategy strategy : strategies) {
            // 检查策略是否支持当前上下文
            if (!strategy.supports(context.invocationContext())) {
                log.debug("Strategy {} does not support current context, skipping",
                         strategy.getName());
                continue;
            }

            try {
                // 创建更新的上下文
                CacheFetchStrategy.CacheFetchContext updatedContext =
                    createUpdatedContext(context, result);

                // 执行策略
                ValueWrapper strategyResult = strategy.fetch(updatedContext);

                if (strategyResult != null) {
                    result = strategyResult;
                    log.debug("Strategy {} returned result", strategy.getName());

                    if (stopOnFirst) {
                        log.debug("StopOnFirst enabled, terminating chain");
                        break;
                    }
                }

                // 检查是否应该停止执行
                if (shouldStopExecution(strategy, strategyResult)) {
                    log.debug("Strategy {} requested chain termination", strategy.getName());
                    break;
                }

            } catch (Exception e) {
                log.warn("Strategy {} execution failed: {}", strategy.getName(), e.getMessage());

                if (!continueOnException || strategy.shouldStopOnException()) {
                    log.debug("Terminating chain due to chain {} failure", strategy.getName());
                    break;
                }
            }
        }

        return result;
    }

    /**
     * 创建更新的上下文。
     */
    private CacheFetchStrategy.CacheFetchContext createUpdatedContext(
            CacheFetchStrategy.CacheFetchContext originalContext, ValueWrapper updatedResult) {
        return new CacheFetchStrategy.CacheFetchContext(
                originalContext.cacheName(),
                originalContext.key(),
                originalContext.cacheKey(),
                updatedResult,
                originalContext.invocation(),
                originalContext.invocationContext(),
                originalContext.redisTemplate(),
                originalContext.callback()
        );
    }

    /**
     * 判断是否应该停止策略执行。
     */
    private boolean shouldStopExecution(CacheFetchStrategy strategy, ValueWrapper result) {
        // 策略返回null且要求停止
        if (result == null && strategy.shouldStopOnNull()) {
            return true;
        }

        // 布隆过滤器阻止访问
        if ("BloomFilter".equals(strategy.getName()) && result == null) {
            log.debug("Bloom filter blocked access, stopping chain");
            return true;
        }

        return false;
    }

    /**
     * 获取策略数量。
     */
    public int size() {
        return strategies != null ? strategies.size() : 0;
    }

    /**
     * 检查是否为空链。
     */
    public boolean isEmpty() {
        return strategies == null || strategies.isEmpty();
    }

    /**
     * 获取策略列表的只读视图。
     */
    public List<CacheFetchStrategy> getStrategies() {
        return strategies != null ? List.copyOf(strategies) : List.of();
    }

    /**
     * 创建简单策略链（仅Simple策略）。
     */
    public static StrategyChain simple(CacheFetchStrategy simpleStrategy) {
        return StrategyChain.builder()
                .strategies(List.of(simpleStrategy))
                .stopOnFirst(true)
                .build();
    }

    /**
     * 创建布隆过滤器策略链。
     */
    public static StrategyChain bloomFilter(CacheFetchStrategy bloomStrategy,
                                          CacheFetchStrategy simpleStrategy) {
        return StrategyChain.builder()
                .strategies(List.of(bloomStrategy, simpleStrategy))
                .build();
    }

    /**
     * 创建预刷新策略链。
     */
    public static StrategyChain preRefresh(CacheFetchStrategy preRefreshStrategy,
                                         CacheFetchStrategy simpleStrategy) {
        return StrategyChain.builder()
                .strategies(List.of(preRefreshStrategy, simpleStrategy))
                .build();
    }

    /**
     * 创建全功能策略链。
     */
    public static StrategyChain fullFeature(CacheFetchStrategy bloomStrategy,
                                          CacheFetchStrategy preRefreshStrategy,
                                          CacheFetchStrategy simpleStrategy) {
        return StrategyChain.builder()
                .strategies(List.of(bloomStrategy, preRefreshStrategy, simpleStrategy))
                .build();
    }
}