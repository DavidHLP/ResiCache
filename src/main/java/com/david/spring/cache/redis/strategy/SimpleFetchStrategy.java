package com.david.spring.cache.redis.strategy;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import lombok.extern.slf4j.Slf4j;
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

	public SimpleFetchStrategy(CacheInvocationRegistry registry, Executor executor,
	                           CacheOperationService cacheOperationService) {
		super(registry, executor, cacheOperationService);
	}

	@Override
	public ValueWrapper fetch(CacheFetchContext context) {
		logDebug("Simple fetch strategy for cache: {}, key: {}",
				context.cacheName(), context.key());

		// 直接返回缓存值
		return context.valueWrapper();
	}

	@Override
	public boolean supports(CachedInvocationContext invocationContext) {
		// 当没有启用任何特殊功能时使用简单策略
		return !invocationContext.distributedLock()
				&& !invocationContext.internalLock()
				&& invocationContext.ttl() <= 0;
	}

	@Override
	public int getOrder() {
		return 100; // 最低优先级
	}
}