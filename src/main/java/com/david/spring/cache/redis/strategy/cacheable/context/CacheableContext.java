package com.david.spring.cache.redis.strategy.cacheable.context;

import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import lombok.Builder;
import lombok.Data;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

/**
 * 缓存策略上下文
 * 组合不同职责的上下文，提供统一的访问接口
 *
 * @param <T> 缓存值类型
 * @author David
 */
@Data
@Builder
public class CacheableContext<T> {

	/** 核心缓存上下文 */
	@NonNull
	private final CacheContext cacheContext;

	/** 保护机制上下文 */
	@Nullable
	private final ProtectionContext protectionContext;

	/** 执行上下文 */
	@Nullable
	private final ExecutionContext executionContext;

	// === 委托方法：CacheContext ===

	public Object getKey() {
		return cacheContext.getKey();
	}

	public String getCacheName() {
		return cacheContext.getCacheName();
	}

	public String createCacheKey() {
		return cacheContext.createRedisKey();
	}

	public org.springframework.cache.Cache getParentCache() {
		return cacheContext.getParentCache();
	}

	public org.springframework.data.redis.core.RedisTemplate<String, Object> getRedisTemplate() {
		return cacheContext.getRedisTemplate();
	}

	public org.springframework.data.redis.cache.RedisCacheConfiguration getCacheConfiguration() {
		return cacheContext.getCacheConfiguration();
	}

	// === 委托方法：ProtectionContext ===

	@Nullable
	public com.david.spring.cache.redis.locks.DistributedLock getDistributedLock() {
		return protectionContext != null ? protectionContext.getDistributedLock() : null;
	}

	@Nullable
	public com.david.spring.cache.redis.protection.CachePenetration getCachePenetration() {
		return protectionContext != null ? protectionContext.getCachePenetration() : null;
	}

	@Nullable
	public com.david.spring.cache.redis.protection.CacheBreakdown getCacheBreakdown() {
		return protectionContext != null ? protectionContext.getCacheBreakdown() : null;
	}

	@Nullable
	public com.david.spring.cache.redis.protection.CacheAvalanche getCacheAvalanche() {
		return protectionContext != null ? protectionContext.getCacheAvalanche() : null;
	}

	// === 委托方法：ExecutionContext ===

	@Nullable
	public java.util.concurrent.Executor getExecutor() {
		return executionContext != null ? executionContext.getExecutor() : null;
	}

	@Nullable
	public com.david.spring.cache.redis.registry.impl.CacheInvocationRegistry getRegistry() {
		return executionContext != null ? executionContext.getRegistry() : null;
	}

	@Nullable
	public CachedInvocation getCachedInvocation() {
		return executionContext != null ?
				executionContext.getCachedInvocation(getCacheName(), getKey()) : null;
	}

	@Nullable
	public CachedInvocationContext getCachedInvocationContext() {
		CachedInvocation invocation = getCachedInvocation();
		return invocation != null ? invocation.getCachedInvocationContext() : null;
	}

	// === 业务逻辑方法 ===

	/**
	 * 判断是否需要缓存保护
	 *
	 * @return 是否需要保护
	 */
	public boolean needsProtection() {
		// 检查是否有保护上下文
		if (protectionContext == null || !protectionContext.hasAnyProtection()) {
			return false;
		}

		// 检查调用上下文是否需要保护
		CachedInvocationContext cic = getCachedInvocationContext();
		if (cic == null) {
			return false;
		}

		return cic.needsProtection() && (
				(cic.useBloomFilter() && protectionContext.supportsPenetrationProtection()) ||
						(cic.distributedLock() && protectionContext.supportsDistributedLock()) ||
						(cic.internalLock() && executionContext != null && executionContext.supportsInvocationRegistry()) ||
						protectionContext.supportsBreakdownProtection() ||
						protectionContext.supportsAvalancheProtection()
		);
	}

	/**
	 * 判断是否支持预刷新
	 *
	 * @return 是否支持预刷新
	 */
	public boolean supportsPreRefresh() {
		if (executionContext == null ||
				!executionContext.supportsAsyncExecution() ||
				!executionContext.supportsInvocationRegistry()) {
			return false;
		}

		CachedInvocationContext cic = getCachedInvocationContext();
		return cic != null && cic.ttl() > 0;
	}

	/**
	 * 构建器类，提供验证和默认值
	 */
	public static class CacheableContextBuilder<T> {

		/**
		 * 构建并验证上下文
		 *
		 * @return 验证后的上下文实例
		 * @throws IllegalArgumentException 如果必要参数缺失
		 */
		public CacheableContext<T> build() {
			if (cacheContext == null) {
				throw new IllegalArgumentException("CacheContext is required");
			}

			// 验证保护上下文的配置
			if (protectionContext != null) {
				// 可以添加额外的验证逻辑
			}

			return new CacheableContext<>(cacheContext, protectionContext, executionContext);
		}
	}
}
