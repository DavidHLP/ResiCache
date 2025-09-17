package com.david.spring.cache.redis.strategy.cacheable.context;

import com.david.spring.cache.redis.locks.DistributedLock;
import com.david.spring.cache.redis.protection.CacheAvalanche;
import com.david.spring.cache.redis.protection.CacheBreakdown;
import com.david.spring.cache.redis.protection.CachePenetration;
import lombok.Builder;
import lombok.Data;
import org.springframework.lang.Nullable;

/**
 * 缓存保护上下文
 * 包含各种缓存保护机制的依赖
 *
 * @author David
 */
@Data
@Builder
public class ProtectionContext {

	/** 分布式锁 */
	@Nullable
	private final DistributedLock distributedLock;

	/** 缓存穿透保护 */
	@Nullable
	private final CachePenetration cachePenetration;

	/** 缓存击穿保护 */
	@Nullable
	private final CacheBreakdown cacheBreakdown;

	/** 缓存雪崩保护 */
	@Nullable
	private final CacheAvalanche cacheAvalanche;

	/**
	 * 检查是否有任何保护机制可用
	 *
	 * @return 如果至少有一种保护机制可用则返回true
	 */
	public boolean hasAnyProtection() {
		return distributedLock != null ||
				cachePenetration != null ||
				cacheBreakdown != null ||
				cacheAvalanche != null;
	}

	/**
	 * 检查是否支持分布式锁
	 *
	 * @return 如果支持分布式锁则返回true
	 */
	public boolean supportsDistributedLock() {
		return distributedLock != null;
	}

	/**
	 * 检查是否支持穿透保护
	 *
	 * @return 如果支持穿透保护则返回true
	 */
	public boolean supportsPenetrationProtection() {
		return cachePenetration != null;
	}

	/**
	 * 检查是否支持击穿保护
	 *
	 * @return 如果支持击穿保护则返回true
	 */
	public boolean supportsBreakdownProtection() {
		return cacheBreakdown != null;
	}

	/**
	 * 检查是否支持雪崩保护
	 *
	 * @return 如果支持雪崩保护则返回true
	 */
	public boolean supportsAvalancheProtection() {
		return cacheAvalanche != null;
	}
}