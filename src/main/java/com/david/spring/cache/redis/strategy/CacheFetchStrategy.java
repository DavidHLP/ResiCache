package com.david.spring.cache.redis.strategy;

import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 缓存获取策略接口
 * 定义不同的缓存获取行为
 */
public interface CacheFetchStrategy {

	/**
	 * 执行缓存获取策略
	 *
	 * @param context 策略上下文
	 * @return 缓存值包装器，如果缓存不存在则返回null
	 */
	ValueWrapper fetch(CacheFetchContext context);

	/**
	 * 判断是否支持该策略
	 *
	 * @param invocationContext 调用上下文
	 * @return true表示支持，false表示不支持
	 */
	boolean supports(CachedInvocationContext invocationContext);

	/**
	 * 获取策略优先级，数字越小优先级越高
	 *
	 * @return 优先级值
	 */
	default int getOrder() {
		return 0;
	}

	default String getName() {
		return this.getClass().getSimpleName();
	}

	default boolean shouldStopOnNull() {
		return false;
	}

	default boolean shouldStopOnException() {
		return false;
	}

	default boolean isValidContext(CacheFetchContext context) {
		return context != null
			&& context.cacheName() != null
			&& context.key() != null
			&& context.invocationContext() != null;
	}

	default boolean isContextCompatible(CacheFetchContext context) {
		if (!isValidContext(context)) {
			return false;
		}

		CachedInvocationContext invocationContext = context.invocationContext();

		// 验证策略类型兼容性
		if (invocationContext.fetchStrategy() != CachedInvocationContext.FetchStrategyType.AUTO
			&& !isStrategyTypeCompatible(invocationContext.fetchStrategy())) {
			return false;
		}

		// 验证必要的依赖项
		return validateContextRequirements(invocationContext);
	}

	default boolean isStrategyTypeCompatible(CachedInvocationContext.FetchStrategyType strategyType) {
		// 默认所有策略都兼容AUTO和SIMPLE类型
		return strategyType == CachedInvocationContext.FetchStrategyType.AUTO
			|| strategyType == CachedInvocationContext.FetchStrategyType.SIMPLE;
	}

	default boolean validateContextRequirements(CachedInvocationContext context) {
		// 基本验证：检查关键配置的一致性
		if (context.ttl() < 0 && context.enablePreRefresh()) {
			return false; // 预刷新需要有效的TTL
		}

		if (context.variance() < 0 || context.variance() > 1) {
			return false; // 方差必须在0-1之间
		}

		if (context.preRefreshThreshold() < 0 || context.preRefreshThreshold() > 1) {
			return false; // 预刷新阈值必须在0-1之间
		}

		return true;
	}

	/**
	 * 缓存获取回调接口
	 */
	interface CacheFetchCallback {
		/**
		 * 获取基础缓存值
		 */
		ValueWrapper getBaseValue(Object key);

		/**
		 * 刷新缓存
		 */
		void refresh(CachedInvocation invocation, Object key, String cacheKey, long ttl);

		/**
		 * 解析配置的TTL时间
		 */
		long resolveConfiguredTtlSeconds(Object value, Object key);

		/**
		 * 判断是否需要预刷新
		 */
		boolean shouldPreRefresh(long ttl, long configuredTtl);
	}

	/**
	 * 策略执行上下文
	 */
	record CacheFetchContext(
			String cacheName,
			Object key,
			String cacheKey,
			ValueWrapper valueWrapper,
			CachedInvocation invocation,
			CachedInvocationContext invocationContext,
			RedisTemplate<String, Object> redisTemplate,
			CacheFetchCallback callback
	) {
	}
}