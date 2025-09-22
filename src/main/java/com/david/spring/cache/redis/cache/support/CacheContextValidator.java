package com.david.spring.cache.redis.cache.support;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.chain.CacheFetchStrategy;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;

/**
 * 缓存上下文验证器
 * 专门负责缓存上下文的验证逻辑
 */
@Slf4j
@Component
public class CacheContextValidator {

	/**
	 * 验证缓存调用上下文
	 */
	public boolean isValidInvocationContext(CachedInvocationContext context) {
		return validateAllRequirements(context);
	}

	/**
	 * 验证策略类型兼容性。
	 */
	public boolean isStrategyTypeCompatible(
			@Nullable CachedInvocationContext.FetchStrategyType strategyType,
			CachedInvocationContext.FetchStrategyType... supportedTypes) {
		Objects.requireNonNull(supportedTypes, "Supported types cannot be null");
		if (strategyType == null) {
			return false;
		}
		if (strategyType == CachedInvocationContext.FetchStrategyType.AUTO) {
			return true;
		}
		return Arrays.asList(supportedTypes).contains(strategyType);
	}

	/**
	 * 验证上下文的基本要求。
	 */
	public boolean validateBasicRequirements(@Nullable CachedInvocationContext context) {
		if (context == null) {
			return false;
		}
		float variance = context.variance();
		if (!isValidPercentage(variance)) {
			return false;
		}
		double preRefreshThreshold = context.preRefreshThreshold();
		return isValidPercentage(preRefreshThreshold);
	}

	/**
	 * 验证预刷新相关配置。
	 */
	public boolean validatePreRefreshRequirements(CachedInvocationContext context) {
		Objects.requireNonNull(context, "Context cannot be null");
		if (!context.enablePreRefresh()) {
			return true;
		}
		if (context.ttl() <= 0) {
			return false;
		}
		double threshold = context.getEffectivePreRefreshThreshold();
		if (!isValidPercentage(threshold) || threshold == 0.0 || threshold == 1.0) {
			return false;
		}
		return context.distributedLock() || context.internalLock();
	}

	/**
	 * 验证布隆过滤器相关配置。
	 */
	public boolean validateBloomFilterRequirements(CachedInvocationContext context) {
		Objects.requireNonNull(context, "Context cannot be null");
		CachedInvocationContext.FetchStrategyType strategyType = context.fetchStrategy();
		if (strategyType == CachedInvocationContext.FetchStrategyType.BLOOM_FILTER) {
			return context.useBloomFilter();
		}
		return true;
	}

	/**
	 * 验证随机 TTL 相关配置。
	 */
	public boolean validateRandomTtlRequirements(CachedInvocationContext context) {
		Objects.requireNonNull(context, "Context cannot be null");
		if (!context.randomTtl()) {
			return true;
		}
		if (context.ttl() <= 0) {
			return false;
		}
		float variance = context.variance();
		return variance > 0.0f && variance <= 1.0f;
	}

	/**
	 * 综合验证策略上下文要求。
	 */
	public boolean validateAllRequirements(@Nullable CachedInvocationContext context) {
		if (context == null) {
			return false;
		}
		return validateBasicRequirements(context)
				&& validatePreRefreshRequirements(context)
				&& validateBloomFilterRequirements(context)
				&& validateRandomTtlRequirements(context);
	}

	/**
	 * 验证百分比值是否在有效范围 [0,1]。
	 */
	private boolean isValidPercentage(double value) {
		return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
	}

	/**
	 * 验证获取上下文
	 */
	public boolean isValidFetchContext(CacheFetchStrategy.CacheFetchContext context) {
		return context != null;
	}

	/**
	 * 判断是否应该执行策略
	 */
	public boolean shouldExecuteStrategies(CachedInvocationContext invocationContext,
	                                       Cache.ValueWrapper baseValue) {
		if (invocationContext.fetchStrategy() == CachedInvocationContext.FetchStrategyType.SIMPLE) {
			return baseValue == null || invocationContext.cacheNullValues();
		}

		if (invocationContext.useBloomFilter()) {
			return true;
		}

		if (invocationContext.enablePreRefresh() && baseValue != null) {
			return true;
		}

		if (baseValue == null) {
			return true;
		}

		return invocationContext.distributedLock() || invocationContext.internalLock();
	}
}