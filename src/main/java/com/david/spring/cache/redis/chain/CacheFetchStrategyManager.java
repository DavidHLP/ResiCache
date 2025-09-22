package com.david.spring.cache.redis.chain;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 缓存获取策略管理器。
 * <p>
 * 负责管理、选择和执行合适的缓存获取策略。使用简化的策略链模式，自动根据上下文选择合适的策略组合。
 * </p>
 *
 * @author CacheGuard
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheFetchStrategyManager {

	/** 策略链工厂 */
	private final StrategyChainFactory chainFactory;

	@PostConstruct
	public void init() {
		log.info("Initializing CacheFetchStrategyManager with simplified chain chain");
	}

	/**
	 * 执行缓存获取策略链。
	 *
	 * @param context 策略执行上下文
	 * @return 策略执行后的缓存值
	 */
	@Nullable
	public ValueWrapper fetch(@Nonnull CacheFetchStrategy.CacheFetchContext context) {
		try {
			// 创建适合当前上下文的策略链
			StrategyChain chain = chainFactory.createChain(context.invocationContext());

			// 执行策略链
			return chain.execute(context);

		} catch (Exception e) {
			log.error("Strategy chain execution failed for cache: {}, key: {}, error: {}",
					context.cacheName(), context.key(), e.getMessage(), e);
			return context.valueWrapper();
		}
	}

	/**
	 * 获取可用的策略名称。
	 */
	public List<String> getAvailableStrategies() {
		return chainFactory.getAvailableStrategies();
	}

	/**
	 * 创建自定义策略链。
	 */
	public StrategyChain createCustomChain(List<String> strategyNames) {
		return chainFactory.createCustomChain(strategyNames);
	}

	/**
	 * 获取策略链缓存统计。
	 */
	public Map<String, Object> getCacheStats() {
		return chainFactory.getCacheStats();
	}

	/**
	 * 清空策略链缓存。
	 */
	public void clearChainCache() {
		chainFactory.clearCache();
	}

	/**
	 * 获取策略信息（用于监控和调试）。
	 */
	public String getStrategyInfo() {
		Map<String, Object> stats = getCacheStats();
		List<String> strategies = getAvailableStrategies();

		return String.format(
			"Strategy Chain Manager:\n" +
			"  Available Strategies: %s\n" +
			"  Chain Cache Size: %s\n" +
			"  Strategy Names: %s",
			strategies.size(),
			stats.get("chainCacheSize"),
			strategies
		);
	}

}