package com.david.spring.cache.redis.strategy;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.factory.RegistryFactory;
import com.david.spring.cache.redis.strategy.support.CacheOperationService;
import com.david.spring.cache.redis.strategy.support.StrategyValidationUtils;
import jakarta.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 缓存获取策略抽象基类。
 * <p>
 * 为所有缓存策略提供通用的基础设施和工具方法，包括缓存操作、锁管理和TTL计算。
 * </p>
 *
 * @author CacheGuard
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractCacheFetchStrategy implements CacheFetchStrategy {

	/** 用于缓存操作的注册表工厂 */
	protected final RegistryFactory registryFactory;

	/** 异步执行器，用于非阻塞操作 */
	protected final Executor executor;

	/** 缓存操作服务，提供核心缓存功能 */
	protected final CacheOperationService cacheOperationService;

	/**
	 * 获取缓存的剩余 TTL（秒）。
	 * <p>
	 * 从 Redis 查询指定键的剩余生存时间。
	 * </p>
	 *
	 * @param context 策略执行上下文
	 * @return TTL 秒数（正数：剩余秒数，-1：永不失效，-2：键不存在）
	 */
	protected long getCacheTtl(@Nonnull CacheFetchContext context) {
		if (context == null) {
			throw new IllegalArgumentException("Context cannot be null");
		}
		return cacheOperationService.getCacheTtl(context.cacheKey(), context.redisTemplate());
	}

	/**
	 * 获取本地锁。
	 * <p>
	 * 用于防止同一 JVM 内的并发问题，锁粒度为缓存名和键的组合。
	 * </p>
	 *
	 * @param context 策略执行上下文
	 * @return 可重入锁实例
	 */
	@Nonnull
	protected ReentrantLock obtainLocalLock(@Nonnull CacheFetchContext context) {
		if (context == null) {
			throw new IllegalArgumentException("Context cannot be null");
		}
		return registryFactory.getCacheInvocationRegistry().obtainLock(context.cacheName(), context.key());
	}

	/**
	 * 记录调试日志。
	 * <p>
	 * 只有在 DEBUG 级别启用时才会执行。
	 * </p>
	 *
	 * @param message 日志消息模板
	 * @param args 消息参数
	 */
	protected void logDebug(@Nonnull String message, Object... args) {
		if (log.isDebugEnabled()) {
			Object[] logArgs = new Object[args.length + 1];
			logArgs[0] = getClass().getSimpleName();
			System.arraycopy(args, 0, logArgs, 1, args.length);
			log.debug("[{}] " + message, logArgs);
		}
	}

	/**
	 * 记录性能日志。
	 *
	 * @param operation 操作名称
	 * @param duration 执行时间（毫秒）
	 * @param context 上下文信息
	 */
	protected void logPerformance(@Nonnull String operation, long duration, @Nonnull CacheFetchContext context) {
		if (duration > 100) {
			log.warn("[{}] Slow operation: {} took {}ms for cache={}, key={}",
					getClass().getSimpleName(), operation, duration, context.cacheName(), context.key());
		} else if (log.isDebugEnabled()) {
			log.debug("[{}] Operation: {} completed in {}ms for cache={}, key={}",
					getClass().getSimpleName(), operation, duration, context.cacheName(), context.key());
		}
	}

	/**
	 * 验证策略类型兼容性。
	 */
	protected boolean isStrategyTypeCompatible(CachedInvocationContext.FetchStrategyType strategyType,
	                                           CachedInvocationContext.FetchStrategyType... supportedTypes) {
		return StrategyValidationUtils.isStrategyTypeCompatible(strategyType, supportedTypes);
	}

	/**
	 * 验证上下文要求。
	 */
	protected boolean validateContextRequirements(CachedInvocationContext context) {
		return StrategyValidationUtils.validateAllRequirements(context);
	}

	/**
	 * 判断是否需要预刷新。
	 */
	protected boolean shouldPreRefresh(long ttl, long configuredTtl) {
		return cacheOperationService.shouldPreRefresh(ttl, configuredTtl);
	}

	/**
	 * 判断是否需要预刷新（支持自定义阈值）。
	 */
	protected boolean shouldPreRefresh(long ttl, long configuredTtl, double threshold) {
		return cacheOperationService.shouldPreRefresh(ttl, configuredTtl, threshold);
	}

	/**
	 * 执行缓存刷新。
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
	 * 根据上下文信息决定是否应该执行策略。
	 */
	protected boolean shouldExecuteStrategy(CacheFetchContext context) {
		if (!isValidContext(context)) {
			return false;
		}

		CachedInvocationContext invocationContext = context.invocationContext();

		if (invocationContext.condition() != null && !invocationContext.condition().isEmpty()) {
			logDebug("Context condition present: %s", invocationContext.condition());
		}

		if (invocationContext.unless() != null && !invocationContext.unless().isEmpty()) {
			logDebug("Context unless condition present: %s", invocationContext.unless());
		}

		return true;
	}

	/**
	 * 获取上下文中的有效 TTL（秒）。
	 * <p>
	 * 计算策略执行中应使用的 TTL 值，会考虑随机化设置以避免缓存雪崩。
	 * </p>
	 *
	 * @param context 策略执行上下文
	 * @return 有效的 TTL 秒数
	 */
	protected long getEffectiveTtl(@Nonnull CacheFetchContext context) {
		CachedInvocationContext invocationContext = context.invocationContext();
		long contextTtl = invocationContext.ttl();

		if (contextTtl > 0) {
			if (invocationContext.randomTtl() && invocationContext.variance() > 0) {
				float variance = invocationContext.variance();
				double randomFactor = 1.0 + (ThreadLocalRandom.current().nextDouble() - 0.5) * 2 * variance;
				long randomizedTtl = Math.round(contextTtl * randomFactor);
				return Math.max(1, randomizedTtl);
			}
			return contextTtl;
		}

		return getCacheTtl(context);
	}

	/**
	 * 检查是否需要使用分布式锁。
	 */
	protected boolean shouldUseDistributedLock(@Nonnull CacheFetchContext context) {
		return context.invocationContext().distributedLock();
	}

	/**
	 * 检查是否需要使用内部锁。
	 */
	protected boolean shouldUseInternalLock(@Nonnull CacheFetchContext context) {
		return context.invocationContext().internalLock();
	}

	/**
	 * 获取分布式锁名称。
	 * <p>
	 * 优先使用用户配置的锁名，否则基于缓存名和键生成默认锁名。
	 * </p>
	 *
	 * @param context 策略执行上下文
	 * @return 分布式锁的键名
	 */
	@Nonnull
	protected String getDistributedLockName(@Nonnull CacheFetchContext context) {
		String configuredLockName = context.invocationContext().distributedLockName();
		if (configuredLockName != null && !configuredLockName.trim().isEmpty()) {
			return configuredLockName.trim();
		}
		return String.format("cache:lock:%s:%s", context.cacheName(), context.key());
	}

	/**
	 * 执行带有性能监控的操作。
	 *
	 * @param operation 操作名称
	 * @param context 执行上下文
	 * @param task 要执行的任务
	 * @param <T> 返回值类型
	 * @return 任务执行结果
	 */
	protected <T> T executeWithMonitoring(@Nonnull String operation,
	                                      @Nonnull CacheFetchContext context,
	                                      @Nonnull java.util.function.Supplier<T> task) {
		long startTime = System.currentTimeMillis();
		try {
			T result = task.get();
			long duration = System.currentTimeMillis() - startTime;
			logPerformance(operation, duration, context);
			return result;
		} catch (Exception e) {
			long duration = System.currentTimeMillis() - startTime;
			log.error("[{}] Operation {} failed after {}ms for cache={}, key={}: {}",
					getClass().getSimpleName(), operation, duration,
					context.cacheName(), context.key(), e.getMessage(), e);
			throw e;
		}
	}
}