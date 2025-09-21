package com.david.spring.cache.redis.strategy;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.factory.RegistryFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * 简单获取策略
 * 直接返回缓存值，不做任何额外处理
 */
@Slf4j
@Component
public class SimpleFetchStrategy extends AbstractCacheFetchStrategy {

	public SimpleFetchStrategy(RegistryFactory registryFactory, @Qualifier("cacheRefreshExecutor") Executor executor,
	                           CacheOperationService cacheOperationService) {
		super(registryFactory, executor, cacheOperationService);
	}

	@Override
	public ValueWrapper fetch(CacheFetchContext context) {
		if (!isValidContext(context)) {
			return null;
		}

		// 基于上下文执行简单的处理逻辑
		ValueWrapper result = context.valueWrapper();
		CachedInvocationContext invocationContext = context.invocationContext();

		// 如果配置了缓存空值且结果为空，返回特殊标记
		if (result == null && invocationContext.cacheNullValues()) {
			logDebug("Returning null value marker for cache: %s, key: %s",
					context.cacheName(), context.key());
		}

		// 如果启用了同步模式，添加日志标记
		if (invocationContext.sync()) {
			logDebug("Synchronous cache access: cache=%s, key=%s, hasValue=%s",
					context.cacheName(), context.key(), result != null);
		}

		return result;
	}

	@Override
	public boolean supports(CachedInvocationContext invocationContext) {
		// 作为默认策略，在以下情况下支持：
		// 1. 明确指定为SIMPLE策略
		// 2. 没有启用任何特殊功能
		// 3. 作为后备策略总是支持
		return invocationContext.fetchStrategy() == CachedInvocationContext.FetchStrategyType.SIMPLE
				|| (!invocationContext.distributedLock()
					&& !invocationContext.internalLock()
					&& !invocationContext.useBloomFilter()
					&& !invocationContext.enablePreRefresh());
	}

	@Override
	public int getOrder() {
		return 100; // 最低优先级
	}
}