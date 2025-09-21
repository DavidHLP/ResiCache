package com.david.spring.cache.redis.strategy;

import com.david.spring.cache.redis.lock.DistributedLock;
import com.david.spring.cache.redis.lock.LockUtils;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.factory.RegistryFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 预刷新策略
 * 当缓存即将过期时，异步刷新缓存
 */
@Slf4j
@Component
public class PreRefreshStrategy extends AbstractCacheFetchStrategy {

	private final DistributedLock distributedLock;

	public PreRefreshStrategy(RegistryFactory registryFactory,
	                          @Qualifier("cacheRefreshExecutor") Executor executor,
	                          DistributedLock distributedLock,
	                          CacheOperationService cacheOperationService) {
		super(registryFactory, executor, cacheOperationService);
		this.distributedLock = distributedLock;
	}

	@Override
	public ValueWrapper fetch(CacheFetchContext context) {
		if (!isValidContext(context) || context.valueWrapper() == null) {
			return context.valueWrapper();
		}

		try {
			long ttl = getCacheTtl(context);
			if (ttl <= 0) {
				return context.valueWrapper();
			}

			long configuredTtl = context.callback()
					.resolveConfiguredTtlSeconds(context.valueWrapper().get(), context.key());

			if (shouldTriggerRefresh(ttl, configuredTtl, context)) {
				triggerAsyncRefresh(context, ttl, configuredTtl);
			}
		} catch (Exception e) {
			log.warn("Pre-refresh failed: cache={}, key={}, error={}",
					context.cacheName(), context.key(), e.getMessage());
		}

		return context.valueWrapper();
	}

	@Override
	public boolean supports(CachedInvocationContext invocationContext) {
		// 支持预刷新功能或有TTL配置且启用了锁机制的场景
		return invocationContext.enablePreRefresh()
				|| (invocationContext.ttl() > 0
					&& (invocationContext.distributedLock() || invocationContext.internalLock()));
	}

	@Override
	public int getOrder() {
		return 10; // 高优先级
	}

	@Override
	public boolean isStrategyTypeCompatible(CachedInvocationContext.FetchStrategyType strategyType) {
		return strategyType == CachedInvocationContext.FetchStrategyType.AUTO
			|| strategyType == CachedInvocationContext.FetchStrategyType.PRE_REFRESH;
	}

	@Override
	public boolean validateContextRequirements(CachedInvocationContext context) {
		// 预刷新特定验证
		if (!super.validateContextRequirements(context)) {
			return false;
		}

		// 预刷新需要有效的TTL或启用预刷新功能
		if (context.enablePreRefresh() && context.ttl() <= 0) {
			return false;
		}

		// 如果启用预刷新，需要有锁机制
		if (context.enablePreRefresh() && !context.distributedLock() && !context.internalLock()) {
			return false;
		}

		return true;
	}

	private boolean shouldTriggerRefresh(long ttl, long configuredTtl, CacheFetchContext context) {
		if (context.invocationContext().enablePreRefresh()) {
			double threshold = context.invocationContext().getEffectivePreRefreshThreshold();
			return ttl >= 0 && shouldPreRefresh(ttl, configuredTtl, threshold);
		}
		return ttl >= 0 && shouldPreRefresh(ttl, configuredTtl);
	}

	private void triggerAsyncRefresh(CacheFetchContext context, long ttl, long configuredTtl) {
		ReentrantLock lock = obtainLocalLock(context);
		executor.execute(() -> {
			try {
				executeRefreshWithLocks(context, lock, ttl, configuredTtl);
			} catch (Exception ex) {
				log.warn("Async refresh failed: cache={}, key={}, error={}",
						context.cacheName(), context.key(), ex.getMessage());
			}
		});
	}

	private void executeRefreshWithLocks(CacheFetchContext context,
	                                     ReentrantLock lock,
	                                     long ttl,
	                                     long configuredTtl) {
		// 第一次检查
		long currentTtl = getCacheTtl(context);
		long currentConfiguredTtl = context.callback()
				.resolveConfiguredTtlSeconds(context.valueWrapper().get(), context.key());

		if (!shouldTriggerRefresh(currentTtl, currentConfiguredTtl, context)) {
			logDebug("Pre-refresh skipped after first-check: name={}, key={}",
					context.cacheName(), context.cacheKey());
			return;
		}

		String distKey = "cache:refresh:" + context.cacheKey();
		long leaseTimeSec = Math.max(5L, Math.min(30L, currentTtl));

		boolean executed = executeLockProtectedRefresh(context, lock, distKey, leaseTimeSec);

		logDebug("Pre-refresh executed={} name={}, key={}",
				executed, context.cacheName(), context.cacheKey());
	}

	private boolean executeLockProtectedRefresh(CacheFetchContext context,
	                                            ReentrantLock lock,
	                                            String distKey,
	                                            long leaseTimeSec) {
		if (context.invocationContext().distributedLock() && distributedLock != null) {
			return LockUtils.runWithLocalTryThenDistTry(
					lock,
					distributedLock,
					distKey,
					0L,
					leaseTimeSec,
					TimeUnit.SECONDS,
					() -> performRefreshIfNeeded(context));
		} else if (context.invocationContext().internalLock()) {
			if (lock.tryLock()) {
				try {
					performRefreshIfNeeded(context);
					return true;
				} finally {
					lock.unlock();
				}
			}
		}
		return false;
	}

	private void performRefreshIfNeeded(CacheFetchContext context) {
		// 第二次检查（获得锁后）
		long finalTtl = getCacheTtl(context);
		long finalConfiguredTtl = context.callback()
				.resolveConfiguredTtlSeconds(context.valueWrapper().get(), context.key());

		if (!shouldTriggerRefresh(finalTtl, finalConfiguredTtl, context)) {
			logDebug("Pre-refresh skipped after lock acquisition: name={}, key={}",
					context.cacheName(), context.cacheKey());
			return;
		}

		logDebug("Pre-refresh starting: name={}, key={}",
				context.cacheName(), context.cacheKey());

		doRefresh(context, finalTtl);
	}
}