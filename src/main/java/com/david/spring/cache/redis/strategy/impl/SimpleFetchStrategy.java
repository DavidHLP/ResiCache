package com.david.spring.cache.redis.strategy.impl;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.factory.RegistryFactory;
import com.david.spring.cache.redis.strategy.AbstractCacheFetchStrategy;
import com.david.spring.cache.redis.cache.support.CacheOperationService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.stereotype.Component;
import java.util.concurrent.Executor;

/**
 * 简单获取策略
 * 最基础的缓存策略，直接返回当前缓存值。
 * 作为默认和后备策略使用。
 *
 * @author DavidHLP
 * @since 1.0.0
 */
@Slf4j
@Component
public class SimpleFetchStrategy extends AbstractCacheFetchStrategy {

	public SimpleFetchStrategy(RegistryFactory registryFactory,
	                           @Qualifier("cacheRefreshExecutor") Executor executor,
	                           CacheOperationService cacheOperationService) {
		super(registryFactory, executor, cacheOperationService);
	}

	@Override
	@Nullable
	public ValueWrapper fetch(@Nonnull CacheFetchContext context) {
		if (isValidContext(context)) {
			logDebug("Invalid context provided, returning null");
			return null;
		}

		return executeWithMonitoring("simple-fetch", context, () -> {
			ValueWrapper result = context.valueWrapper();
			CachedInvocationContext invocationContext = context.invocationContext();
			boolean hasValue = context.hasValue();

			// 处理空值缓存
			if (!hasValue && invocationContext.cacheNullValues()) {
				logDebug("Returning null value marker for cache: {}, key: {}",
						context.cacheName(), context.key());
			}

			// 同步模式处理
			if (invocationContext.sync()) {
				logDebug("Synchronous cache access: cache={}, key={}, hasValue={}",
						context.cacheName(), context.key(), hasValue);
			}

			// 调试信息
			if (log.isTraceEnabled()) {
				Object value = context.getValue();
				log.trace("[{}] Simple fetch completed: cache={}, key={}, valueType={}, hasValue={}",
						getClass().getSimpleName(), context.cacheName(), context.key(),
						value != null ? value.getClass().getSimpleName() : "null", hasValue);
			}

			return result;
		});
	}

	@Override
	public boolean supports(@Nonnull CachedInvocationContext invocationContext) {
		CachedInvocationContext.FetchStrategyType strategyType = invocationContext.fetchStrategy();

		// 明确指定为SIMPLE策略时支持
		if (strategyType == CachedInvocationContext.FetchStrategyType.SIMPLE) {
			return true;
		}

		// AUTO模式下，无特殊功能时作为默认策略
		if (strategyType == CachedInvocationContext.FetchStrategyType.AUTO) {
			return isBasicCacheScenario(invocationContext);
		}

		return false;
	}

	/**
	 * 判断是否为基础缓存场景
	 */
	private boolean isBasicCacheScenario(@Nonnull CachedInvocationContext context) {
		return !context.distributedLock()
				&& !context.internalLock()
				&& !context.useBloomFilter()
				&& !context.enablePreRefresh();
	}

	@Override
	public int getOrder() {
		return DEFAULT_ORDER; // 最低优先级
	}

	@Override
	@Nonnull
	public String getName() {
		return "Simple";
	}
}