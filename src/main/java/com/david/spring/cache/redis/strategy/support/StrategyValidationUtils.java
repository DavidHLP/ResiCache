package com.david.spring.cache.redis.strategy.support;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Objects;

/**
 * 策略验证工具类。
 * <p>
 * 为缓存策略提供通用验证逻辑，确保参数和配置符合预期。
 * </p>
 *
 * @author CacheGuard
 * @since 1.0.0
 */
@Slf4j
public final class StrategyValidationUtils {

	private StrategyValidationUtils() {
		throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
	}

	/**
	 * 验证策略类型兼容性。
	 * <p>
	 * 检查指定策略类型是否与支持的类型兼容。`AUTO` 类型总是兼容的。
	 * </p>
	 *
	 * @param strategyType   要验证的策略类型
	 * @param supportedTypes 支持的策略类型列表
	 * @return true表示兼容
	 * @throws IllegalArgumentException 如果 `supportedTypes` 为 null
	 */
	public static boolean isStrategyTypeCompatible(
			@Nullable CachedInvocationContext.FetchStrategyType strategyType,
			@Nonnull CachedInvocationContext.FetchStrategyType... supportedTypes) {
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
	 *
	 * @param context 要验证的上下文
	 * @return true表示验证通过
	 */
	public static boolean validateBasicRequirements(@Nullable CachedInvocationContext context) {
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
	 * 验证百分比值是否在有效范围 `[0,1]`。
	 */
	private static boolean isValidPercentage(double value) {
		return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
	}

	/**
	 * 验证预刷新相关配置。
	 *
	 * @param context 要验证的上下文
	 * @return true表示验证通过
	 * @throws IllegalArgumentException 如果 `context` 为 null
	 */
	public static boolean validatePreRefreshRequirements(@Nonnull CachedInvocationContext context) {
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
	 *
	 * @param context 要验证的上下文
	 * @return true表示验证通过
	 * @throws IllegalArgumentException 如果 `context` 为 null
	 */
	public static boolean validateBloomFilterRequirements(@Nonnull CachedInvocationContext context) {
		Objects.requireNonNull(context, "Context cannot be null");
		CachedInvocationContext.FetchStrategyType strategyType = context.fetchStrategy();
		if (strategyType == CachedInvocationContext.FetchStrategyType.BLOOM_FILTER) {
			return context.useBloomFilter();
		}
		return true;
	}

	/**
	 * 验证锁相关配置。
	 *
	 * @param context 要验证的上下文
	 * @return 总是返回 true
	 * @throws IllegalArgumentException 如果 `context` 为 null
	 */
	public static boolean validateLockRequirements(@Nonnull CachedInvocationContext context) {
		Objects.requireNonNull(context, "Context cannot be null");
		return true;
	}

	/**
	 * 验证随机 TTL 相关配置。
	 *
	 * @param context 要验证的上下文
	 * @return true表示验证通过
	 * @throws IllegalArgumentException 如果 `context` 为 null
	 */
	public static boolean validateRandomTtlRequirements(@Nonnull CachedInvocationContext context) {
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
	 *
	 * @param context 要验证的上下文
	 * @return true表示所有验证都通过
	 */
	public static boolean validateAllRequirements(@Nullable CachedInvocationContext context) {
		if (context == null) {
			return false;
		}
		return validateBasicRequirements(context)
				&& validatePreRefreshRequirements(context)
				&& validateBloomFilterRequirements(context)
				&& validateLockRequirements(context)
				&& validateRandomTtlRequirements(context);
	}

}