package com.david.spring.cache.redis.strategy.cacheable;

import com.david.spring.cache.redis.strategy.cacheable.context.CacheableContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * 缓存获取策略管理器
 * 根据上下文选择合适的策略执行缓存获取操作
 *
 * @author David
 */
@Getter
@Slf4j
@Component
public class CacheableStrategyManager {

	/**
	 * -- GETTER --
	 * 获取可用的策略列表
	 *
	 */
	private final List<CacheableStrategy<Object>> strategies;

	public CacheableStrategyManager(List<CacheableStrategy<Object>> strategies) {
		// 按优先级排序策略
		this.strategies = strategies.stream()
				.sorted(AnnotationAwareOrderComparator.INSTANCE)
				.toList();

		log.info("Initialized CacheableStrategyManager with {} strategies: {}",
				strategies.size(),
				strategies.stream().map(s -> s.getClass().getSimpleName()).toList());
	}

	/**
	 * 执行缓存获取操作
	 *
	 * @param context 缓存获取上下文
	 * @return 缓存值包装器
	 */
	@Nullable
	public Cache.ValueWrapper get(@NonNull CacheableContext<Object> context) {
		log.debug("Executing cache get for key: {} using strategy manager", context.getKey());

		return executeWithStrategy(context, strategy -> strategy.get(context),
			() -> null, "cache get");
	}

	/**
	 * 执行带值加载器的缓存获取操作
	 *
	 * @param context     缓存获取上下文
	 * @param valueLoader 值加载器
	 * @return 缓存值
	 */
	@Nullable
	public <V> V get(@NonNull CacheableContext<Object> context, @NonNull Callable<V> valueLoader) {
		log.debug("Executing cache get with value loader for key: {} using strategy manager", context.getKey());

		return executeWithStrategy(context, strategy -> strategy.get(context, valueLoader),
			() -> {
				try {
					log.debug("Falling back to direct value loader invocation for key: {}", context.getKey());
					return valueLoader.call();
				} catch (Exception e) {
					log.error("Direct value loader invocation failed for key: {}", context.getKey(), e);
					throw new Cache.ValueRetrievalException(context.getKey(), valueLoader, e);
				}
			}, "cache get with value loader");
	}

	/**
	 * 使用策略执行操作的通用方法
	 */
	@Nullable
	private <T> T executeWithStrategy(@NonNull CacheableContext<Object> context,
									  @NonNull StrategyExecutor<T> executor,
									  @NonNull FallbackExecutor<T> fallback,
									  @NonNull String operationType) {

		for (CacheableStrategy<Object> strategy : strategies) {
			if (strategy.supports(context)) {
				String strategyName = strategy.getClass().getSimpleName();
				log.debug("Using strategy: {} for key: {} ({})", strategyName, context.getKey(), operationType);

				try {
					T result = executor.execute(strategy);
					log.debug("Strategy {} completed for key: {} ({}), result: {}",
							strategyName, context.getKey(), operationType,
							result != null ? "success" : "null");
					return result;
				} catch (Exception e) {
					log.error("Strategy {} failed for key: {} ({}), trying next strategy",
							strategyName, context.getKey(), operationType, e);
					// 继续尝试下一个策略
				}
			}
		}

		log.warn("No suitable strategy found for key: {} ({})", context.getKey(), operationType);
		try {
			return fallback.execute();
		} catch (Exception e) {
			log.error("Fallback execution failed for key: {} ({})", context.getKey(), operationType, e);
			if (e instanceof RuntimeException) {
				throw (RuntimeException) e;
			} else {
				throw new RuntimeException("Fallback execution failed", e);
			}
		}
	}

	@FunctionalInterface
	private interface StrategyExecutor<T> {
		@Nullable
		T execute(CacheableStrategy<Object> strategy) throws Exception;
	}

	@FunctionalInterface
	private interface FallbackExecutor<T> {
		@Nullable
		T execute() throws Exception;
	}

	/**
	 * 查找支持指定上下文的第一个策略
	 *
	 * @param context 缓存获取上下文
	 * @return 支持的策略，如果没有找到则返回null
	 */
	@Nullable
	public CacheableStrategy<Object> findSupportedStrategy(@NonNull CacheableContext<Object> context) {
		return strategies.stream()
				.filter(strategy -> strategy.supports(context))
				.findFirst()
				.orElse(null);
	}
}
