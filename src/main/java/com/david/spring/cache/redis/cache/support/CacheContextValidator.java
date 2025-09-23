package com.david.spring.cache.redis.cache.support;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.chain.CacheHandlerContext;
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
	 * 验证处理器上下文
	 */
	public boolean isValidHandlerContext(CacheHandlerContext context) {
		return context != null;
	}

	/**
	 * 判断是否应该执行处理器链
	 */
	public boolean shouldExecuteHandlers(CachedInvocationContext invocationContext,
	                                    Cache.ValueWrapper baseValue) {
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