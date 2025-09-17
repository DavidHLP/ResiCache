package com.david.spring.cache.redis.strategy.cacheable.context;

import com.david.spring.cache.redis.locks.DistributedLock;
import com.david.spring.cache.redis.protection.CacheAvalanche;
import com.david.spring.cache.redis.protection.CacheBreakdown;
import com.david.spring.cache.redis.protection.CachePenetration;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.impl.CacheInvocationRegistry;
import lombok.Builder;
import lombok.Data;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;

import java.util.concurrent.Executor;

/**
 * 缓存获取策略上下文
 * 包含缓存获取操作所需的所有信息和依赖
 *
 * @param <T> 缓存值类型
 * @author David
 */
@Data
@Builder
public class CacheGetContext<T> {

	/** 缓存键 */
	private Object key;

	/** 缓存名称 */
	private String cacheName;

	/** 父缓存实例 */
	private Cache parentCache;

	/** Redis 模板 */
	private RedisTemplate<String, Object> redisTemplate;

	/** 缓存配置 */
	private RedisCacheConfiguration cacheConfiguration;

	/** 缓存调用注册中心 */
	private CacheInvocationRegistry registry;

	/** 异步执行器 */
	private Executor executor;

	/** 分布式锁 */
	private DistributedLock distributedLock;

	/** 缓存穿透保护 */
	private CachePenetration cachePenetration;

	/** 缓存击穿保护 */
	private CacheBreakdown cacheBreakdown;

	/** 缓存雪崩保护 */
	private CacheAvalanche cacheAvalanche;

	/** 缓存调用信息（可选） */
	@Builder.Default
	private CachedInvocation cachedInvocation = null;

	/**
	 * 创建缓存键字符串
	 *
	 * @return 缓存键字符串
	 */
	public String createCacheKey() {
		return cacheName + "::" + key.toString();
	}

	/**
	 * 获取缓存调用信息
	 *
	 * @return 缓存调用信息
	 */
	public CachedInvocation getCachedInvocation() {
		if (cachedInvocation == null && registry != null) {
			cachedInvocation = registry.get(cacheName, key).orElse(null);
		}
		return cachedInvocation;
	}

	/**
	 * 获取缓存调用上下文
	 *
	 * @return 缓存调用上下文
	 */
	@Nullable
	public CachedInvocationContext getCachedInvocationContext() {
		CachedInvocation invocation = getCachedInvocation();
		return (invocation != null) ? invocation.getCachedInvocationContext() : null;
	}

	/**
	 * 判断是否需要缓存保护
	 *
	 * @return 是否需要保护
	 */
	public boolean needsProtection() {
		CachedInvocationContext cic = getCachedInvocationContext();
		if (cic == null) {
			return false; // No context, no specific protection
		}
		// Protection is needed if any of the annotation flags are set and the corresponding component is available
		boolean needsPenetrationProtection = cic.useBloomFilter() && cachePenetration != null;
		// breakdown protection can be achieved via distributed lock or local internal lock
		boolean hasDistLock = cic.distributedLock() && distributedLock != null;
		boolean hasLocalLock = cic.internalLock() && registry != null;
		boolean needsBreakdownProtection = (hasDistLock || hasLocalLock) && cacheBreakdown != null;
		// Avalanche protection is more about TTL, but we can consider it always on if component is present
		boolean needsAvalancheProtection = cacheAvalanche != null;

		return needsPenetrationProtection || needsBreakdownProtection || needsAvalancheProtection;
	}

	/**
	 * 判断是否支持预刷新
	 *
	 * @return 是否支持预刷新
	 */
	public boolean supportsPreRefresh() {
		if (executor == null || registry == null) {
			return false;
		}
		CachedInvocationContext cic = getCachedInvocationContext();
		// Pre-refresh is useful when there is a TTL.
		return cic != null && cic.ttl() > 0;
	}
}
