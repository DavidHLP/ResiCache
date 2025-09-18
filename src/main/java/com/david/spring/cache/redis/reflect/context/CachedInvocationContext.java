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
		float variance) {
}
