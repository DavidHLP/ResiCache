package com.david.spring.cache.redis.chain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 简化策略链使用示例。
 * <p>
 * 演示如何使用新的简化策略链系统。
 * </p>
 *
 * @author CacheGuard
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cache.chain.example.enabled", havingValue = "true")
public class StrategyChainExample implements CommandLineRunner {

    private final CacheFetchStrategyManager strategyManager;

    @Override
    public void run(String... args) {
        log.info("=== 简化策略链示例开始 ===");

        // 1. 显示可用策略
        showAvailableStrategies();

        // 2. 演示自定义策略链
        demonstrateCustomChains();

        // 3. 显示缓存统计
        showCacheStats();

        log.info("=== 简化策略链示例结束 ===");
    }

    /**
     * 显示可用策略。
     */
    private void showAvailableStrategies() {
        log.info("\n--- 可用策略 ---");
        List<String> strategies = strategyManager.getAvailableStrategies();
        log.info("可用策略数量: {}", strategies.size());
        strategies.forEach(strategy -> log.info("  - {}", strategy));
    }

    /**
     * 演示自定义策略链。
     */
    private void demonstrateCustomChains() {
        log.info("\n--- 自定义策略链示例 ---");

        // 创建不同的策略链组合
        StrategyChain simpleChain = strategyManager.createCustomChain(List.of("Simple"));
        log.info("简单策略链: {} 个策略", simpleChain.size());

        StrategyChain bloomChain = strategyManager.createCustomChain(List.of("BloomFilter", "Simple"));
        log.info("布隆过滤器策略链: {} 个策略", bloomChain.size());

        StrategyChain preRefreshChain = strategyManager.createCustomChain(List.of("PreRefresh", "Simple"));
        log.info("预刷新策略链: {} 个策略", preRefreshChain.size());

        StrategyChain fullChain = strategyManager.createCustomChain(
                List.of("BloomFilter", "PreRefresh", "Simple"));
        log.info("全功能策略链: {} 个策略", fullChain.size());

        // 演示策略链信息
        log.info("全功能策略链详情:");
        fullChain.getStrategies().forEach(strategy ->
                log.info("  策略: {} (优先级: {})", strategy.getName(), strategy.getOrder()));
    }

    /**
     * 显示缓存统计。
     */
    private void showCacheStats() {
        log.info("\n--- 缓存统计 ---");
        Map<String, Object> stats = strategyManager.getCacheStats();
        stats.forEach((key, value) -> log.info("{}: {}", key, value));

        log.info("\n策略信息:");
        log.info(strategyManager.getStrategyInfo());
    }

    /**
     * 演示最佳实践。
     */
    public void demonstrateBestPractices() {
        log.info("\n--- 最佳实践示例 ---");

        // 1. 高频访问场景：布隆过滤器 + 预刷新
        StrategyChain highFrequencyChain = strategyManager.createCustomChain(
                List.of("BloomFilter", "PreRefresh", "Simple"));
        log.info("高频访问策略链: {}", highFrequencyChain.getStrategies().stream()
                .map(CacheFetchStrategy::getName).toList());

        // 2. 简单场景：仅使用简单策略
        StrategyChain basicChain = strategyManager.createCustomChain(List.of("Simple"));
        log.info("简单场景策略链: {}", basicChain.getStrategies().stream()
                .map(CacheFetchStrategy::getName).toList());

        // 3. 防穿透场景：主要使用布隆过滤器
        StrategyChain antiPenetrationChain = strategyManager.createCustomChain(
                List.of("BloomFilter", "Simple"));
        log.info("防穿透策略链: {}", antiPenetrationChain.getStrategies().stream()
                .map(CacheFetchStrategy::getName).toList());

        // 4. 预刷新场景：保持热点数据
        StrategyChain hotDataChain = strategyManager.createCustomChain(
                List.of("PreRefresh", "Simple"));
        log.info("热点数据策略链: {}", hotDataChain.getStrategies().stream()
                .map(CacheFetchStrategy::getName).toList());
    }
}