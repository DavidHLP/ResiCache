package com.david.spring.cache.redis.reflect.context;

import com.david.spring.cache.redis.reflect.interfaces.InvocationContext;
import lombok.Builder;
import org.springframework.lang.Nullable;

/**
 * 缓存调用上下文实现类
 * 主人，这个类实现了通用的缓存上下文接口喵~
 */
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

		boolean useSecondLevelCache,

		boolean distributedLock,

		String distributedLockName,

		boolean internalLock,

		boolean cacheNullValues,

		boolean useBloomFilter,

		boolean randomTtl,

		float variance) implements InvocationContext {

	@Override
	public String[] getCacheNames() {
		return cacheNames != null ? cacheNames : (value != null ? value : new String[0]);
	}

	@Override
	@Nullable
	public String getKey() {
		return key;
	}

	@Override
	@Nullable
	public String getCondition() {
		return condition;
	}

	@Override
	@Nullable
	public String getKeyGenerator() {
		return keyGenerator;
	}

	@Override
	@Nullable
	public String getCacheManager() {
		return cacheManager;
	}

	@Override
	@Nullable
	public String getCacheResolver() {
		return cacheResolver;
	}

	@Override
	public boolean isSync() {
		return sync;
	}

	@Override
	public String getContextType() {
		return "CachedInvocation";
	}

	/**
	 * 获取不缓存的条件表达式（Cached特有）
	 *
	 * @return 不缓存的条件表达式
	 */
	@Nullable
	public String getUnless() {
		return unless;
	}

	/**
	 * 获取缓存过期时间（Cached特有）
	 *
	 * @return 缓存过期时间（毫秒）
	 */
	public long getTtl() {
		return ttl;
	}

	/**
	 * 获取目标类型（Cached特有）
	 *
	 * @return 目标类型
	 */
	@Nullable
	public Class<?> getType() {
		return type;
	}
}
