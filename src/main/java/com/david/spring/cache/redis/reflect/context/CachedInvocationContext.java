package com.david.spring.cache.redis.reflect.context;

import lombok.Builder;

/** 缓存调用上下文记录类 */
@Builder
public record CachedInvocationContext(
		/* 缓存名称数组 */
		String[] cacheNames,

		/* 缓存键表达式 */
		String key,

		/* 缓存条件表达式 */
		String condition,

		/* 是否同步执行 */
		boolean sync,

		/* 缓存值表达式 */
		String[] value,

		/* KeyGenerator Bean名称 */
		String keyGenerator,

		/* CacheManager Bean名称 */
		String cacheManager,

		/* CacheResolver Bean名称 */
		String cacheResolver,

		/* 不缓存的条件表达式 */
		String unless,

		/* 缓存过期时间（毫秒） */
		long ttl,

		/* 目标类型 */
		Class<?> type,

		/* 是否使用二级缓存 */
		boolean useSecondLevelCache,

		/* 是否使用分布式锁 */
		boolean distributedLock,

		/* 分布式锁名称 */
		String distributedLockName,

		/* 是否使用内部锁 */
		boolean internalLock,

		/* 是否缓存空值 */
		boolean cacheNullValues,

		/* 是否使用布隆过滤器 */
		boolean useBloomFilter,

		/* 是否使用随机TTL */
		boolean randomTtl,

		/* TTL方差 */
		float variance,

		/* 缓存获取策略类型 */
		FetchStrategyType fetchStrategy,

		/* 是否启用预刷新 */
		boolean enablePreRefresh,

		/* 预刷新阈值百分比（当剩余TTL低于总TTL的此百分比时触发） */
		double preRefreshThreshold,

		/* 自定义策略类名 */
		String customStrategyClass) {

	/**
	 * 获取默认的预刷新阈值
	 */
	public double getEffectivePreRefreshThreshold() {
		return preRefreshThreshold > 0 ? preRefreshThreshold : 0.3;
	}

	/**
	 * 缓存获取策略类型枚举
	 */
	public enum FetchStrategyType {
		/** 自动选择策略 */
		AUTO,
		/** 简单获取策略 */
		SIMPLE,
		/** 预刷新策略 */
		PRE_REFRESH,
		/** 布隆过滤器策略 */
		BLOOM_FILTER,
		/** 自定义策略 */
		CUSTOM
	}
}
