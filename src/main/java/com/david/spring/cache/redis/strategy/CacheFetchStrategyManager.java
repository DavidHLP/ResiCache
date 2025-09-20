package com.david.spring.cache.redis.strategy;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
		sortedStrategies = new ArrayList<>(strategies);
		sortedStrategies.sort(Comparator.comparingInt(CacheFetchStrategy::getOrder));
		log.info("Initialized {} cache fetch strategies", sortedStrategies.size());
	}

	/**
	 * 执行缓存获取策略链
	 */
	public ValueWrapper fetch(CacheFetchStrategy.CacheFetchContext context) {
		if (context.invocationContext() == null) {
			log.debug("No invocation context provided, returning original value");
			return context.valueWrapper();
		}

		// 基于上下文预选择合适的策略，避免每次都重新计算
		List<CacheFetchStrategy> applicableStrategies = getOptimizedStrategies(context);
		if (applicableStrategies.isEmpty()) {
			log.warn("No applicable strategies found for cache: {}, key: {}", context.cacheName(), context.key());
			return context.valueWrapper();
		}

		if (log.isDebugEnabled()) {
			log.debug("Executing {} optimized strategies for cache: {}, key: {}: [{}]",
					applicableStrategies.size(), context.cacheName(), context.key(),
					applicableStrategies.stream().map(CacheFetchStrategy::getName).reduce((a, b) -> a + ", " + b).orElse(""));
		}

		ValueWrapper result = context.valueWrapper();
		int strategyIndex = 0;

		for (CacheFetchStrategy strategy : applicableStrategies) {
			strategyIndex++;
			long startTime = System.currentTimeMillis();

			try {
				// 快速兼容性检查（大部分检查已在策略选择时完成）
				if (!strategy.isValidContext(context)) {
					log.debug("Strategy {} context validation failed, skipping", strategy.getName());
					continue;
				}

				// 创建更新的上下文，传递当前结果
				CacheFetchStrategy.CacheFetchContext updatedContext = createUpdatedContext(context, result);

				if (log.isTraceEnabled()) {
					log.trace("Executing strategy {}/{}: {} for cache: {}, key: {}",
							strategyIndex, applicableStrategies.size(), strategy.getName(),
							context.cacheName(), context.key());
				}

				ValueWrapper strategyResult = strategy.fetch(updatedContext);
				long duration = System.currentTimeMillis() - startTime;

				// 记录策略执行结果
				logStrategyResult(strategy, strategyResult, duration, context);

				// 更新结果
				if (strategyResult != null) {
					result = strategyResult;
				}

				// 检查是否应该停止执行后续策略
				if (shouldStopExecution(strategy, strategyResult, context)) {
					log.debug("Stopping strategy execution after {} due to result: {}",
							strategy.getName(), strategyResult != null ? "non-null" : "null");
					break;
				}

			} catch (Exception e) {
				long duration = System.currentTimeMillis() - startTime;
				handleStrategyException(strategy, e, duration, context);

				if (strategy.shouldStopOnException()) {
					log.warn("Stopping strategy execution after exception in {}", strategy.getName());
					break;
				}
			}
		}

		if (log.isDebugEnabled()) {
			log.debug("Strategy chain execution completed for cache: {}, key: {}, final result: {}",
					context.cacheName(), context.key(), result != null ? "present" : "null");
		}

		return result;
	}

	/**
	 * 获取优化的策略列表（基于上下文和当前状态）
	 */
	private List<CacheFetchStrategy> getOptimizedStrategies(CacheFetchStrategy.CacheFetchContext context) {
		CachedInvocationContext invocationContext = context.invocationContext();
		ValueWrapper currentValue = context.valueWrapper();

		// 基于当前状态进行策略优化选择
		List<CacheFetchStrategy> strategies = getApplicableStrategies(invocationContext);

		// 如果有值且策略类型是 SIMPLE，只保留必要的策略
		if (currentValue != null && invocationContext.fetchStrategy() == CachedInvocationContext.FetchStrategyType.SIMPLE) {
			strategies = strategies.stream()
					.filter(s -> s instanceof SimpleFetchStrategy)
					.toList();
		}

		// 如果没有值，优先布隆过滤器策略
		if (currentValue == null && invocationContext.useBloomFilter()) {
			List<CacheFetchStrategy> bloomStrategies = strategies.stream()
					.filter(s -> s instanceof BloomFilterStrategy)
					.toList();
			if (!bloomStrategies.isEmpty()) {
				// 如果有布隆过滤器策略，让它先执行
				List<CacheFetchStrategy> optimized = new ArrayList<>(bloomStrategies);
				strategies.stream()
						.filter(s -> !(s instanceof BloomFilterStrategy))
						.forEach(optimized::add);
				strategies = optimized;
			}
		}

		return strategies;
	}

	/**
	 * 获取适用的策略列表
	 */
	private List<CacheFetchStrategy> getApplicableStrategies(CachedInvocationContext context) {
		List<CacheFetchStrategy> applicable = new ArrayList<>();

		// 确保策略类型不为null
		CachedInvocationContext.FetchStrategyType strategyType = context.fetchStrategy();
		if (strategyType == null) {
			log.warn("Strategy type is null in context, defaulting to AUTO");
			strategyType = CachedInvocationContext.FetchStrategyType.AUTO;
		}

		// 根据上下文的策略类型进行优化选择
		if (strategyType != CachedInvocationContext.FetchStrategyType.AUTO) {
			applicable = getStrategiesByType(strategyType, context);
		} else {
			// 自动模式：查找所有支持的策略
			for (CacheFetchStrategy strategy : sortedStrategies) {
				try {
					if (strategy.supports(context)) {
						applicable.add(strategy);
					}
				} catch (Exception e) {
					log.warn("Strategy {} support check failed: {}", strategy.getName(), e.getMessage());
				}
			}
		}

		// 确保至少有一个策略可用
		ensureDefaultStrategy(applicable);

		// 根据上下文信息进行策略排序优化
		applicable = optimizeStrategyOrder(applicable, context);

		if (log.isDebugEnabled()) {
			log.debug("Selected {} applicable strategies for context {}: {}",
					applicable.size(),
					strategyType,
					applicable.stream().map(CacheFetchStrategy::getName).toList());
		}

		return applicable;
	}

	private List<CacheFetchStrategy> getStrategiesByType(CachedInvocationContext.FetchStrategyType type, CachedInvocationContext context) {
		List<CacheFetchStrategy> strategies = new ArrayList<>();

		// 处理null类型，默认返回空列表
		if (type == null) {
			log.warn("Strategy type is null, returning empty strategy list");
			return strategies;
		}

		switch (type) {
			case SIMPLE:
				strategies.addAll(sortedStrategies.stream()
						.filter(SimpleFetchStrategy.class::isInstance)
						.toList());
				break;
			case BLOOM_FILTER:
				strategies.addAll(sortedStrategies.stream()
						.filter(s -> s instanceof BloomFilterStrategy && s.supports(context))
						.toList());
				break;
			case PRE_REFRESH:
				strategies.addAll(sortedStrategies.stream()
						.filter(s -> s instanceof PreRefreshStrategy && s.supports(context))
						.toList());
				break;
			case CUSTOM:
				if (context.customStrategyClass() != null && !context.customStrategyClass().isEmpty()) {
					strategies.addAll(sortedStrategies.stream()
							.filter(s -> s.getClass().getName().equals(context.customStrategyClass()))
							.toList());
				}
				break;
			case AUTO:
				// AUTO类型不应该在这里处理，但为了安全起见添加此case
				log.debug("AUTO strategy type should not be handled in getStrategiesByType");
				break;
			default:
				log.warn("Unknown strategy type: {}", type);
				break;
		}

		return strategies;
	}

	private List<CacheFetchStrategy> optimizeStrategyOrder(List<CacheFetchStrategy> strategies, CachedInvocationContext context) {
		// 根据上下文特征调整策略优先级
		return strategies.stream()
				.sorted((s1, s2) -> {
					int priority1 = calculateContextPriority(s1, context);
					int priority2 = calculateContextPriority(s2, context);

					// 优先级高的在前（数字小的优先级高）
					if (priority1 != priority2) {
						return Integer.compare(priority1, priority2);
					}

					// 优先级相同时按原始order排序
					return Integer.compare(s1.getOrder(), s2.getOrder());
				})
				.toList();
	}

	private int calculateContextPriority(CacheFetchStrategy strategy, CachedInvocationContext context) {
		int priority = strategy.getOrder();

		// 根据上下文特征调整优先级
		if (strategy instanceof BloomFilterStrategy && context.useBloomFilter()) {
			priority -= 5; // 提高布隆过滤器优先级
		}

		if (strategy instanceof PreRefreshStrategy && context.enablePreRefresh()) {
			priority -= 3; // 提高预刷新优先级
		}

		// 如果上下文指定了分布式锁，对支持锁的策略提高优先级
		if ((context.distributedLock() || context.internalLock()) &&
			strategy instanceof PreRefreshStrategy) {
			priority -= 2;
		}

		return priority;
	}

	private void ensureDefaultStrategy(List<CacheFetchStrategy> applicable) {
		if (applicable.isEmpty()) {
			// 查找 SimpleFetchStrategy 作为默认策略
			SimpleFetchStrategy defaultStrategy = findDefaultStrategy();
			if (defaultStrategy != null) {
				applicable.add(defaultStrategy);
				log.debug("No applicable strategies found, using default strategy: {}", defaultStrategy.getName());
			} else {
				log.warn("No default strategy available!");
			}
		}
	}

	private SimpleFetchStrategy findDefaultStrategy() {
		return sortedStrategies.stream()
				.filter(SimpleFetchStrategy.class::isInstance)
				.map(SimpleFetchStrategy.class::cast)
				.findFirst()
				.orElse(null);
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

	/**
	 * 创建更新的上下文
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
	 * 记录策略执行结果
	 */
	private void logStrategyResult(CacheFetchStrategy strategy, ValueWrapper result,
	                               long duration, CacheFetchStrategy.CacheFetchContext context) {
		if (log.isDebugEnabled()) {
			String resultStatus = result != null ? "success" : "null";
			if (duration > 50) { // 记录较慢的操作
				log.debug("Strategy {} execution: result={}, duration={}ms, cache={}, key={}",
						strategy.getName(), resultStatus, duration, context.cacheName(), context.key());
			}
		}

		// 记录慢策略警告
		if (duration > 200) {
			log.warn("Slow strategy execution: {} took {}ms for cache={}, key={}",
					strategy.getName(), duration, context.cacheName(), context.key());
		}
	}

	/**
	 * 处理策略异常
	 */
	private void handleStrategyException(CacheFetchStrategy strategy, Exception e,
	                                     long duration, CacheFetchStrategy.CacheFetchContext context) {
		log.error("Strategy {} failed after {}ms for cache={}, key={}: {}",
				strategy.getName(), duration, context.cacheName(), context.key(), e.getMessage());

		// 对于特定类型的异常，提供更详细的日志
		if (e instanceof IllegalStateException) {
			log.debug("Strategy configuration issue in {}: {}", strategy.getName(), e.getMessage());
		} else if (e instanceof RuntimeException) {
			log.debug("Runtime error in strategy {}", strategy.getName(), e);
		}
	}

	/**
	 * 判断是否应该停止策略执行
	 */
	private boolean shouldStopExecution(CacheFetchStrategy strategy, ValueWrapper result,
	                                    CacheFetchStrategy.CacheFetchContext context) {
		// 如果策略返回null且配置为停止
		if (result == null && strategy.shouldStopOnNull()) {
			return true;
		}

		// 布隆过滤器特殊处理：如果返回null，说明被过滤，应该停止
		if (strategy instanceof BloomFilterStrategy && result == null) {
			log.debug("Bloom filter blocked access, stopping strategy chain");
			return true;
		}

		// 检查是否是终止性策略结果
		return isTerminalResult(result, context);
	}

	/**
	 * 判断结果是否为终止性结果
	 */
	private boolean isTerminalResult(ValueWrapper result, CacheFetchStrategy.CacheFetchContext context) {
		// 如果配置不允许缓存空值，且结果为空，则继续尝试其他策略
		if (result == null && !context.invocationContext().cacheNullValues()) {
			return false;
		}

		// 其他情况下，有结果就可能是终止性的
		return result != null;
	}

	/**
	 * 获取策略执行信息（用于监控和调试）
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