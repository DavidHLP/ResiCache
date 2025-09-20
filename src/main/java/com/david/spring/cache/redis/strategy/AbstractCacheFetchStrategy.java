package com.david.spring.cache.redis.strategy;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 缓存获取策略抽象基类
 * 提供通用的策略实现基础
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractCacheFetchStrategy implements CacheFetchStrategy {

	protected final CacheInvocationRegistry registry;
	protected final Executor executor;
	protected final CacheOperationService cacheOperationService;

	/**
	 * 获取缓存TTL
	 */
	protected long getCacheTtl(CacheFetchContext context) {
		return cacheOperationService.getCacheTtl(context.cacheKey(), context.redisTemplate());
	}

	/**
	 * 获取本地锁
	 */
	protected ReentrantLock obtainLocalLock(CacheFetchContext context) {
		return registry.obtainLock(context.cacheName(), context.key());
	}

	protected void logDebug(String message, Object... args) {
		if (log.isDebugEnabled()) {
			log.debug("[{}] {}", this.getClass().getSimpleName(), String.format(message, args));
		}
	}

	protected void logInfo(String message, Object... args) {
		log.info("[{}] {}", this.getClass().getSimpleName(), String.format(message, args));
	}

	protected void logWarn(String message, Object... args) {
		log.warn("[{}] {}", this.getClass().getSimpleName(), String.format(message, args));
	}

	protected void logError(String message, Exception e, Object... args) {
		log.error("[{}] {}", this.getClass().getSimpleName(), String.format(message, args), e);
	}

	protected boolean isContextValid(CacheFetchContext context) {
		return context != null
				&& context.cacheName() != null
				&& context.key() != null
				&& context.redisTemplate() != null;
	}

	protected <T> T executeWithTiming(String operation, CacheFetchContext context, java.util.function.Supplier<T> supplier) {
		long startTime = System.currentTimeMillis();
		try {
			T result = supplier.get();
			long duration = System.currentTimeMillis() - startTime;
			if (duration > 100) {
				log.warn("[{}] Slow {} operation: cache={}, key={}, duration={}ms",
						this.getClass().getSimpleName(), operation, context.cacheName(), context.key(), duration);
			}
			return result;
		} catch (Exception e) {
			long duration = System.currentTimeMillis() - startTime;
			log.error("[{}] {} failed: cache={}, key={}, duration={}ms",
					this.getClass().getSimpleName(), operation, context.cacheName(), context.key(), duration, e);
			throw e;
		}
	}

	/**
	 * 判断是否需要预刷新
	 */
	protected boolean shouldPreRefresh(long ttl, long configuredTtl) {
		return cacheOperationService.shouldPreRefresh(ttl, configuredTtl);
	}

	/**
	 * 判断是否需要预刷新（支持自定义阈值）
	 */
	protected boolean shouldPreRefresh(long ttl, long configuredTtl, double threshold) {
		return cacheOperationService.shouldPreRefresh(ttl, configuredTtl, threshold);
	}

	/**
	 * 执行缓存刷新
	 */
	protected void doRefresh(CacheFetchContext context, long ttl) {
		CacheOperationService.CacheRefreshCallback callback = new CacheOperationService.CacheRefreshCallback() {
			@Override
			public void putCache(Object key, Object value) {
				context.callback().refresh(context.invocation(), key, context.cacheKey(), ttl);
			}

			@Override
			public String getCacheName() {
				return context.cacheName();
			}
		};

		cacheOperationService.doRefresh(context.invocation(), context.key(),
				context.cacheKey(), ttl, callback);
	}

	/**
	 * 根据上下文信息决定是否应该执行策略
	 */
	protected boolean shouldExecuteStrategy(CacheFetchContext context) {
		if (!isContextValid(context)) {
			return false;
		}

		CachedInvocationContext invocationContext = context.invocationContext();

		// 检查条件表达式（简化版）
		if (invocationContext.condition() != null && !invocationContext.condition().isEmpty()) {
			// 这里可以添加条件表达式评估逻辑
			logDebug("Context condition present: %s", invocationContext.condition());
		}

		// 检查unless表达式（简化版）
		if (invocationContext.unless() != null && !invocationContext.unless().isEmpty()) {
			// 这里可以添加unless表达式评估逻辑
			logDebug("Context unless condition present: %s", invocationContext.unless());
		}

		return true;
	}

	/**
	 * 获取上下文中的有效TTL
	 */
	protected long getEffectiveTtl(CacheFetchContext context) {
		CachedInvocationContext invocationContext = context.invocationContext();
		long contextTtl = invocationContext.ttl();

		if (contextTtl > 0) {
			// 如果启用了随机TTL，应用方差
			if (invocationContext.randomTtl() && invocationContext.variance() > 0) {
				float variance = invocationContext.variance();
				// 简化的随机TTL计算：基础TTL ± variance%
				double randomFactor = 1.0 + (Math.random() - 0.5) * 2 * variance;
				return Math.max(1, (long) (contextTtl * randomFactor));
			}
			return contextTtl;
		}

		// 回退到Redis TTL
		return getCacheTtl(context);
	}

	/**
	 * 检查是否需要使用分布式锁
	 */
	protected boolean shouldUseDistributedLock(CacheFetchContext context) {
		return context.invocationContext().distributedLock();
	}

	/**
	 * 检查是否需要使用内部锁
	 */
	protected boolean shouldUseInternalLock(CacheFetchContext context) {
		return context.invocationContext().internalLock();
	}

	/**
	 * 获取分布式锁名称
	 */
	protected String getDistributedLockName(CacheFetchContext context) {
		String lockName = context.invocationContext().distributedLockName();
		if (lockName != null && !lockName.isEmpty()) {
			return lockName;
		}
		return "cache:lock:" + context.cacheName() + ":" + context.key();
	}
}