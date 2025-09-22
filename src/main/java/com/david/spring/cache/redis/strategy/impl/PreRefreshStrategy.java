package com.david.spring.cache.redis.strategy.impl;

import com.david.spring.cache.redis.lock.DistributedLock;
import com.david.spring.cache.redis.lock.LockUtils;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.factory.RegistryFactory;
import com.david.spring.cache.redis.strategy.AbstractCacheFetchStrategy;
import com.david.spring.cache.redis.strategy.support.CacheOperationService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 预刷新策略。
 * <p>
 * 当缓存即将过期时，异步触发缓存刷新，确保热点数据始终可用，
 * 有效避免缓存穿透和并发加载问题。
 * </p>
 *
 * @author CacheGuard
 * @since 1.0.0
 */
@Slf4j
@Component
public class PreRefreshStrategy extends AbstractCacheFetchStrategy {

	/** 分布式锁服务，用于跨实例的同步控制 */
	@Nullable
	private final DistributedLock distributedLock;

	public PreRefreshStrategy(RegistryFactory registryFactory,
	                          @Qualifier("cacheRefreshExecutor") Executor executor,
	                          @Nullable DistributedLock distributedLock,
	                          CacheOperationService cacheOperationService) {
		super(registryFactory, executor, cacheOperationService);
		this.distributedLock = distributedLock;
	}

	@Override
	@Nullable
	public ValueWrapper fetch(@Nonnull CacheFetchContext context) {
		// 早期验证和快速路径
		if (!isValidContext(context) || !context.hasValue()) {
			logDebug("Invalid context or no value, skipping pre-refresh");
			return context.valueWrapper();
		}

		if (!context.invocationContext().enablePreRefresh()) {
			logDebug("Pre-refresh disabled, skipping check");
			return context.valueWrapper();
		}

		return executeWithMonitoring("pre-refresh", context, () -> {
			try {
				long ttl = getCacheTtl(context);
				if (ttl <= 0) {
					logDebug("Cache expired or non-existent, skipping pre-refresh: cache={}, key={}",
							context.cacheName(), context.key());
					return context.valueWrapper();
				}

				// 避免空值时的TTL解析
				Object cacheValue = context.getValue();
				if (cacheValue == null && !context.invocationContext().cacheNullValues()) {
					logDebug("Null value without cacheNullValues, skipping pre-refresh: cache={}, key={}",
							context.cacheName(), context.key());
					return context.valueWrapper();
				}

				long configuredTtl = context.callback()
						.resolveConfiguredTtlSeconds(cacheValue, context.key());

				if (shouldTriggerRefresh(ttl, configuredTtl, context)) {
					triggerAsyncRefresh(context, ttl, configuredTtl);
				} else {
					logDebug("Pre-refresh not needed: cache={}, key={}, ttl={}, configuredTtl={}",
							context.cacheName(), context.key(), ttl, configuredTtl);
				}
			} catch (Exception e) {
				log.warn("[{}] Pre-refresh failed: cache={}, key={}, error={}",
						getClass().getSimpleName(), context.cacheName(), context.key(), e.getMessage(), e);
			}

			return context.valueWrapper();
		});
	}

	@Override
	public boolean supports(@Nonnull CachedInvocationContext invocationContext) {
		// 更精确地支持条件判断
		CachedInvocationContext.FetchStrategyType strategyType = invocationContext.fetchStrategy();
		if (strategyType == CachedInvocationContext.FetchStrategyType.PRE_REFRESH) {
			return true;
		}

		if (strategyType == CachedInvocationContext.FetchStrategyType.AUTO) {
			return invocationContext.enablePreRefresh()
					|| isPreRefreshCandidate(invocationContext);
		}
		return false;
	}

	/**
	 * 判断是否为预刷新候选场景。
	 * <p>
	 * 有效的 TTL 配置和启用锁机制的场景。
	 * </p>
	 */
	private boolean isPreRefreshCandidate(@Nonnull CachedInvocationContext context) {
		return context.ttl() > 0
				&& (context.distributedLock() || context.internalLock())
				&& hasValidPreRefreshConfiguration(context);
	}

	/**
	 * 检查预刷新配置是否有效。
	 * <p>
	 * 预刷新阈值应在 (0.1, 0.9) 范围内。
	 * </p>
	 */
	private boolean hasValidPreRefreshConfiguration(@Nonnull CachedInvocationContext context) {
		double threshold = context.getEffectivePreRefreshThreshold();
		return threshold > 0.0 && threshold < 1.0;
	}

	@Override
	public int getOrder() {
		return HIGH_PRIORITY;
	}

	@Override
	@Nonnull
	public String getName() {
		return "PreRefresh";
	}

	/**
	 * 判断是否应该触发刷新操作。
	 */
	private boolean shouldTriggerRefresh(long ttl, long configuredTtl, @Nonnull CacheFetchContext context) {
		if (ttl < 0 || configuredTtl <= 0) {
			logDebug("Invalid TTL values: currentTtl={}, configuredTtl={}", ttl, configuredTtl);
			return false;
		}

		CachedInvocationContext invocationContext = context.invocationContext();
		if (invocationContext.enablePreRefresh()) {
			double threshold = invocationContext.getEffectivePreRefreshThreshold();
			if (threshold <= 0.0 || threshold >= 1.0) {
				logDebug("Invalid pre-refresh threshold: {}, using default", threshold);
				return shouldPreRefresh(ttl, configuredTtl);
			}
			return shouldPreRefresh(ttl, configuredTtl, threshold);
		}
		return shouldPreRefresh(ttl, configuredTtl);
	}

	/**
	 * 触发异步刷新操作。
	 */
	private void triggerAsyncRefresh(@Nonnull CacheFetchContext context, long ttl, long configuredTtl) {
		logDebug("Triggering async refresh: cache={}, key={}, ttl={}, configuredTtl={}",
				context.cacheName(), context.key(), ttl, configuredTtl);

		ReentrantLock lock = obtainLocalLock(context);

		executor.execute(() -> {
			String threadName = Thread.currentThread().getName();
			try {
				logDebug("Starting async refresh in thread: {}, cache={}, key={}",
						threadName, context.cacheName(), context.key());
				executeRefreshWithLocks(context, lock, ttl, configuredTtl);
			} catch (Exception ex) {
				log.error("[{}] Async refresh failed in thread {}: cache={}, key={}, error={}",
						getClass().getSimpleName(), threadName,
						context.cacheName(), context.key(), ex.getMessage(), ex);
			}
		});
	}

	/**
	 * 在锁保护下执行刷新操作。
	 * <p>
	 * 使用双重检查锁定模式，避免重复刷新。
	 * </p>
	 */
	private void executeRefreshWithLocks(@Nonnull CacheFetchContext context,
	                                     @Nonnull ReentrantLock lock,
	                                     long ttl,
	                                     long configuredTtl) {
		// 第一次检查，避免不必要地锁竞争
		long currentTtl = getCacheTtl(context);
		Object currentValue = context.getValue();
		long currentConfiguredTtl = context.callback()
				.resolveConfiguredTtlSeconds(currentValue, context.key());

		if (!shouldTriggerRefresh(currentTtl, currentConfiguredTtl, context)) {
			logDebug("Pre-refresh skipped after first-check: cache={}, key={}, currentTtl={}, configuredTtl={}",
					context.cacheName(), context.key(), currentTtl, currentConfiguredTtl);
			return;
		}

		// 动态计算锁租约时间
		String distKey = getDistributedLockName(context) + ":refresh";
		long leaseTimeSec = calculateOptimalLeaseTime(currentTtl, configuredTtl);

		boolean executed = executeLockProtectedRefresh(context, lock, distKey, leaseTimeSec);

		logDebug("Pre-refresh execution completed: executed={}, cache={}, key={}, leaseTime={}s",
				executed, context.cacheName(), context.key(), leaseTimeSec);
	}

	/**
	 * 计算最优锁租约时间。
	 */
	private long calculateOptimalLeaseTime(long currentTtl, long configuredTtl) {
		long baseTtl = Math.max(currentTtl, configuredTtl);
		long calculatedLease = Math.max(baseTtl / 10, baseTtl / 2);
		return Math.max(5L, Math.min(60L, calculatedLease));
	}

	/**
	 * 在锁保护下执行刷新操作。
	 * <p>
	 * 根据配置选择使用分布式锁或本地锁。
	 * </p>
	 */
	private boolean executeLockProtectedRefresh(@Nonnull CacheFetchContext context,
	                                            @Nonnull ReentrantLock lock,
	                                            @Nonnull String distKey,
	                                            long leaseTimeSec) {
		CachedInvocationContext invocationContext = context.invocationContext();

		if (invocationContext.distributedLock() && distributedLock != null) {
			logDebug("Using distributed lock for refresh: key={}, leaseTime={}s", distKey, leaseTimeSec);
			try {
				return LockUtils.runWithLocalTryThenDistTry(
						lock,
						distributedLock,
						distKey,
						0L,
						leaseTimeSec,
						TimeUnit.SECONDS,
						() -> performRefreshIfNeeded(context));
			} catch (Exception e) {
				log.warn("[{}] Distributed lock execution failed: cache={}, key={}, error={}",
						getClass().getSimpleName(), context.cacheName(), context.key(), e.getMessage());
				return false;
			}
		}

		if (invocationContext.internalLock()) {
			logDebug("Using internal lock for refresh: cache={}, key={}", context.cacheName(), context.key());
			if (lock.tryLock()) {
				try {
					performRefreshIfNeeded(context);
					return true;
				} catch (Exception e) {
					log.warn("[{}] Local lock execution failed: cache={}, key={}, error={}",
							getClass().getSimpleName(), context.cacheName(), context.key(), e.getMessage());
					return false;
				} finally {
					lock.unlock();
				}
			} else {
				logDebug("Failed to acquire internal lock: cache={}, key={}", context.cacheName(), context.key());
			}
		} else {
			logDebug("No lock required, executing refresh directly: cache={}, key={}",
					context.cacheName(), context.key());
			try {
				performRefreshIfNeeded(context);
				return true;
			} catch (Exception e) {
				log.warn("[{}] Direct refresh execution failed: cache={}, key={}, error={}",
						getClass().getSimpleName(), context.cacheName(), context.key(), e.getMessage());
				return false;
			}
		}

		return false;
	}

	/**
	 * 在获得锁后执行实际的刷新操作。
	 * <p>
	 * 使用双重检查确保在获得锁后仍然需要刷新。
	 * </p>
	 */
	private void performRefreshIfNeeded(@Nonnull CacheFetchContext context) {
		// 第二次检查（获得锁后）
		long finalTtl = getCacheTtl(context);
		Object finalValue = context.getValue();
		long finalConfiguredTtl = context.callback()
				.resolveConfiguredTtlSeconds(finalValue, context.key());

		if (!shouldTriggerRefresh(finalTtl, finalConfiguredTtl, context)) {
			logDebug("Pre-refresh skipped after lock acquisition: cache={}, key={}, finalTtl={}, configuredTtl={}",
					context.cacheName(), context.key(), finalTtl, finalConfiguredTtl);
			return;
		}

		logDebug("Pre-refresh starting execution: cache={}, key={}, finalTtl={}, configuredTtl={}",
				context.cacheName(), context.key(), finalTtl, finalConfiguredTtl);

		long startTime = System.currentTimeMillis();
		try {
			doRefresh(context, finalTtl);
			long duration = System.currentTimeMillis() - startTime;
			logDebug("Pre-refresh completed successfully: cache={}, key={}, duration={}ms",
					context.cacheName(), context.key(), duration);
		} catch (Exception e) {
			long duration = System.currentTimeMillis() - startTime;
			log.error("[{}] Pre-refresh execution failed: cache={}, key={}, duration={}ms, error={}",
					getClass().getSimpleName(), context.cacheName(), context.key(),
					duration, e.getMessage(), e);
			throw e;
		}
	}
}