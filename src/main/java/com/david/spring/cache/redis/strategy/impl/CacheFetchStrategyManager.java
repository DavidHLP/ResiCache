package com.david.spring.cache.redis.strategy.impl;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.strategy.CacheFetchStrategy;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 缓存获取策略管理器。
 * <p>
 * 负责管理、选择和执行合适的缓存获取策略。采用责任链模式，按优先级顺序执行策略。
 * </p>
 *
 * @author CacheGuard
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheFetchStrategyManager {

	/** 所有注册的策略（Spring自动注入） */
	@Resource
	private final List<CacheFetchStrategy> strategies = new ArrayList<>();
	/** 按优先级排序的策略列表（只读） */
	private volatile List<CacheFetchStrategy> sortedStrategies;

	@PostConstruct
	public void init() {
		List<CacheFetchStrategy> sorted = new ArrayList<>(strategies);
		sorted.sort(Comparator.comparingInt(CacheFetchStrategy::getOrder));
		this.sortedStrategies = Collections.unmodifiableList(sorted);

		log.info("Initialized {} cache fetch strategies: {}",
				sortedStrategies.size(),
				sortedStrategies.stream()
						.map(s -> s.getName() + "(order:" + s.getOrder() + ")")
						.toList());

		if (log.isDebugEnabled()) {
			log.debug("Strategy initialization details:\n{}", getStrategyInfo());
		}
	}

	/**
	 * 执行缓存获取策略链。
	 *
	 * @param context 策略执行上下文
	 * @return 策略执行后的缓存值
	 */
	@Nullable
	public ValueWrapper fetch(@Nonnull CacheFetchStrategy.CacheFetchContext context) {
		List<CacheFetchStrategy> applicableStrategies = getOptimizedStrategies(context);
		if (applicableStrategies.isEmpty()) {
			return context.valueWrapper();
		}

		ValueWrapper result = context.valueWrapper();

		for (CacheFetchStrategy strategy : applicableStrategies) {
			try {
				if (!strategy.isValidContext(context)) {
					continue;
				}

				CacheFetchStrategy.CacheFetchContext updatedContext = createUpdatedContext(context, result);
				ValueWrapper strategyResult = strategy.fetch(updatedContext);

				if (strategyResult != null) {
					result = strategyResult;
				}

				if (shouldStopExecution(strategy, strategyResult, context)) {
					break;
				}

			} catch (Exception e) {
				log.warn("Strategy {} execution failed: {}", strategy.getName(), e.getMessage());
				if (strategy.shouldStopOnException()) {
					break;
				}
			}
		}

		return result;
	}

	/**
	 * 获取优化的策略列表。
	 */
	private List<CacheFetchStrategy> getOptimizedStrategies(CacheFetchStrategy.CacheFetchContext context) {
		return getApplicableStrategies(context.invocationContext());
	}

	/**
	 * 获取适用的策略列表。
	 */
	private List<CacheFetchStrategy> getApplicableStrategies(CachedInvocationContext context) {
		List<CacheFetchStrategy> applicable = new ArrayList<>();
		CachedInvocationContext.FetchStrategyType strategyType = context.fetchStrategy();
		if (strategyType == null) {
			strategyType = CachedInvocationContext.FetchStrategyType.AUTO;
		}

		if (strategyType != CachedInvocationContext.FetchStrategyType.AUTO) {
			applicable = getStrategiesByType(strategyType, context);
		} else {
			for (CacheFetchStrategy strategy : sortedStrategies) {
				if (strategy.supports(context)) {
					applicable.add(strategy);
				}
			}
		}

		if (applicable.isEmpty()) {
			SimpleFetchStrategy defaultStrategy = findDefaultStrategy();
			if (defaultStrategy != null) {
				applicable.add(defaultStrategy);
			}
		}

		return applicable;
	}

	private List<CacheFetchStrategy> getStrategiesByType(CachedInvocationContext.FetchStrategyType type, CachedInvocationContext context) {
		List<CacheFetchStrategy> strategies = new ArrayList<>();
		if (type == null) {
			log.warn("Strategy type is null, returning empty strategy list");
			return strategies;
		}
		switch (type) {
			case SIMPLE:
				strategies.addAll(sortedStrategies.stream().filter(SimpleFetchStrategy.class::isInstance).toList());
				break;
			case BLOOM_FILTER:
				strategies.addAll(sortedStrategies.stream().filter(s -> s instanceof BloomFilterStrategy && s.supports(context)).toList());
				break;
			case PRE_REFRESH:
				strategies.addAll(sortedStrategies.stream().filter(s -> s instanceof PreRefreshStrategy && s.supports(context)).toList());
				break;
			case CUSTOM:
				if (context.customStrategyClass() != null && !context.customStrategyClass().isEmpty()) {
					strategies.addAll(sortedStrategies.stream().filter(s -> s.getClass().getName().equals(context.customStrategyClass())).toList());
				}
				break;
			case AUTO:
				log.debug("AUTO strategy type should not be handled in getStrategiesByType");
				break;
			default:
				log.warn("Unknown strategy type: {}", type);
				break;
		}
		return strategies;
	}


	private SimpleFetchStrategy findDefaultStrategy() {
		return sortedStrategies.stream()
				.filter(SimpleFetchStrategy.class::isInstance)
				.map(SimpleFetchStrategy.class::cast)
				.findFirst()
				.orElse(null);
	}

	/**
	 * 获取所有已注册的策略。
	 */
	public List<CacheFetchStrategy> getAllStrategies() {
		return Collections.unmodifiableList(sortedStrategies);
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
	private boolean shouldStopExecution(CacheFetchStrategy strategy, ValueWrapper result,
	                                    CacheFetchStrategy.CacheFetchContext context) {
		if (result == null && strategy.shouldStopOnNull()) {
			return true;
		}
		if (strategy instanceof BloomFilterStrategy && result == null) {
			log.debug("Bloom filter blocked access, stopping strategy chain");
			return true;
		}
		return isTerminalResult(result, context);
	}

	/**
	 * 判断结果是否为终止性结果。
	 */
	private boolean isTerminalResult(ValueWrapper result, CacheFetchStrategy.CacheFetchContext context) {
		if (result == null && !context.invocationContext().cacheNullValues()) {
			return false;
		}
		return result != null;
	}

	/**
	 * 获取策略执行信息（用于监控和调试）。
	 */
	public String getStrategyInfo() {
		StringBuilder info = new StringBuilder();
		info.append("Registered Strategies (").append(sortedStrategies.size()).append("):\n");
		for (int i = 0; i < sortedStrategies.size(); i++) {
			CacheFetchStrategy strategy = sortedStrategies.get(i);
			info.append("  ").append(i + 1).append(". ")
					.append(strategy.getName())
					.append(" (order: ").append(strategy.getOrder()).append(")")
					.append(" [stopOnNull: ").append(strategy.shouldStopOnNull())
					.append(", stopOnException: ").append(strategy.shouldStopOnException()).append("]\n");
		}
		return info.toString();
	}

}