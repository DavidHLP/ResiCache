package com.david.spring.cache.redis.chain;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import jakarta.annotation.Nonnull;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 策略链工厂。
 * <p>
 * 负责根据调用上下文创建合适的策略链，提供简单优雅的策略组合。
 * </p>
 *
 * @author CacheGuard
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyChainFactory {

    /** 所有已注册的策略 */
    private final List<CacheFetchStrategy> allStrategies;

    /** 策略映射 */
    private Map<String, CacheFetchStrategy> strategyMap;

    /** 策略链缓存 */
    private final Map<String, StrategyChain> chainCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 构建策略映射
        strategyMap = allStrategies.stream()
                .collect(Collectors.toMap(
                        CacheFetchStrategy::getName,
                        strategy -> strategy
                ));

        log.info("Initialized chain chain factory with {} strategies: {}",
                strategyMap.size(), strategyMap.keySet());
    }

    /**
     * 根据上下文创建策略链。
     *
     * @param context 缓存调用上下文
     * @return 策略链
     */
    @Nonnull
    public StrategyChain createChain(@Nonnull CachedInvocationContext context) {
        String cacheKey = buildCacheKey(context);

        return chainCache.computeIfAbsent(cacheKey, key -> buildChain(context));
    }

    /**
     * 根据上下文构建策略链。
     */
    private StrategyChain buildChain(CachedInvocationContext context) {
        // 1. 根据明确指定的策略类型构建
        CachedInvocationContext.FetchStrategyType strategyType = context.fetchStrategy();
        if (strategyType != null && strategyType != CachedInvocationContext.FetchStrategyType.AUTO) {
            StrategyChain explicitChain = buildExplicitChain(strategyType);
            if (explicitChain != null) {
                log.debug("Created explicit chain chain for type: {}", strategyType);
                return explicitChain;
            }
        }

        // 2. 根据功能特性自动构建
        StrategyChain autoChain = buildAutoChain(context);
        log.debug("Created auto chain chain with {} strategies", autoChain.size());
        return autoChain;
    }

    /**
     * 根据明确指定的策略类型构建链。
     */
    private StrategyChain buildExplicitChain(CachedInvocationContext.FetchStrategyType strategyType) {
        CacheFetchStrategy simpleStrategy = strategyMap.get("Simple");
        CacheFetchStrategy bloomStrategy = strategyMap.get("BloomFilter");
        CacheFetchStrategy preRefreshStrategy = strategyMap.get("PreRefresh");

        return switch (strategyType) {
            case SIMPLE -> StrategyChain.simple(simpleStrategy);
            case BLOOM_FILTER -> bloomStrategy != null ?
                    StrategyChain.bloomFilter(bloomStrategy, simpleStrategy) :
                    StrategyChain.simple(simpleStrategy);
            case PRE_REFRESH -> preRefreshStrategy != null ?
                    StrategyChain.preRefresh(preRefreshStrategy, simpleStrategy) :
                    StrategyChain.simple(simpleStrategy);
            default -> null;
        };
    }

    /**
     * 根据功能特性自动构建链。
     */
    private StrategyChain buildAutoChain(CachedInvocationContext context) {
        CacheFetchStrategy simpleStrategy = strategyMap.get("Simple");
        CacheFetchStrategy bloomStrategy = strategyMap.get("BloomFilter");
        CacheFetchStrategy preRefreshStrategy = strategyMap.get("PreRefresh");

        // 检查需要的功能
        boolean needBloom = context.useBloomFilter() && bloomStrategy != null;
        boolean needPreRefresh = shouldUsePreRefresh(context) && preRefreshStrategy != null;

        // 根据功能组合构建策略链
        if (needBloom && needPreRefresh) {
            return StrategyChain.fullFeature(bloomStrategy, preRefreshStrategy, simpleStrategy);
        } else if (needBloom) {
            return StrategyChain.bloomFilter(bloomStrategy, simpleStrategy);
        } else if (needPreRefresh) {
            return StrategyChain.preRefresh(preRefreshStrategy, simpleStrategy);
        } else {
            return StrategyChain.simple(simpleStrategy);
        }
    }

    /**
     * 判断是否应该使用预刷新。
     */
    private boolean shouldUsePreRefresh(CachedInvocationContext context) {
        return context.enablePreRefresh()
                || (context.ttl() > 0 && (context.distributedLock() || context.internalLock()));
    }

    /**
     * 构建缓存键。
     */
    private String buildCacheKey(CachedInvocationContext context) {
        return String.format("%s:%s:%s:%s",
                context.fetchStrategy(),
                context.useBloomFilter(),
                context.enablePreRefresh(),
                shouldUsePreRefresh(context));
    }

    /**
     * 清空策略链缓存。
     */
    public void clearCache() {
        chainCache.clear();
        log.debug("Strategy chain cache cleared");
    }

    /**
     * 获取缓存统计。
     */
    public Map<String, Object> getCacheStats() {
        return Map.of(
                "chainCacheSize", chainCache.size(),
                "availableStrategies", strategyMap.size(),
                "strategyNames", strategyMap.keySet()
        );
    }

    /**
     * 创建自定义策略链。
     */
    public StrategyChain createCustomChain(List<String> strategyNames) {
        List<CacheFetchStrategy> strategies = strategyNames.stream()
                .map(strategyMap::get)
                .filter(strategy -> strategy != null)
                .toList();

        if (strategies.isEmpty()) {
            log.warn("No valid strategies found for names: {}", strategyNames);
            return StrategyChain.simple(strategyMap.get("Simple"));
        }

        return StrategyChain.builder()
                .strategies(strategies)
                .build();
    }

    /**
     * 获取可用的策略名称。
     */
    public List<String> getAvailableStrategies() {
        return strategyMap.keySet().stream().sorted().toList();
    }
}