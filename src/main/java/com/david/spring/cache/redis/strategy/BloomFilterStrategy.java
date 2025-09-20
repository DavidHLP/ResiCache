package com.david.spring.cache.redis.strategy;

import com.david.spring.cache.redis.protection.CachePenetration;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * 布隆过滤器策略
 * 使用布隆过滤器防止缓存穿透
 */
@Slf4j
@Component
public class BloomFilterStrategy extends AbstractCacheFetchStrategy {

	private final CachePenetration cachePenetration;

	public BloomFilterStrategy(CacheInvocationRegistry registry,
	                           @Qualifier("cacheRefreshExecutor") Executor executor,
	                           CacheOperationService cacheOperationService,
	                           CachePenetration cachePenetration) {
		super(registry, executor, cacheOperationService);
		this.cachePenetration = cachePenetration;
	}

	@Override
	public ValueWrapper fetch(CacheFetchContext context) {
		if (!isContextValid(context) || !context.invocationContext().useBloomFilter()) {
			return context.valueWrapper();
		}

		CachedInvocationContext invocationContext = context.invocationContext();

		if (context.valueWrapper() == null) {
			if (!checkBloomFilter(context)) {
				logDebug("Bloom filter blocked key: cache=%s, key=%s", context.cacheName(), context.key());

				// 如果配置了缓存空值，仍然阻止查询
				if (invocationContext.cacheNullValues()) {
					logDebug("Bloom filter blocking despite cacheNullValues=true: cache=%s, key=%s",
							context.cacheName(), context.key());
				}

				return null;
			}
		} else {
			// 只有在值非空时才更新布隆过滤器
			if (context.valueWrapper().get() != null || invocationContext.cacheNullValues()) {
				updateBloomFilter(context);
			}
		}

		return context.valueWrapper();
	}

	@Override
	public boolean supports(CachedInvocationContext invocationContext) {
		// 支持布隆过滤器功能或明确指定为BLOOM_FILTER策略
		return invocationContext.useBloomFilter()
				|| invocationContext.fetchStrategy() == CachedInvocationContext.FetchStrategyType.BLOOM_FILTER;
	}

	@Override
	public int getOrder() {
		return 5; // 在预刷新之前执行
	}

	@Override
	public boolean shouldStopOnNull() {
		return true; // 布隆过滤器返回null时应该停止后续策略
	}

	@Override
	public boolean isStrategyTypeCompatible(CachedInvocationContext.FetchStrategyType strategyType) {
		return strategyType == CachedInvocationContext.FetchStrategyType.AUTO
			|| strategyType == CachedInvocationContext.FetchStrategyType.BLOOM_FILTER;
	}

	@Override
	public boolean validateContextRequirements(CachedInvocationContext context) {
		// 布隆过滤器特定验证
		if (!super.validateContextRequirements(context)) {
			return false;
		}

		// 布隆过滤器应该启用
		if (!context.useBloomFilter() && context.fetchStrategy() == CachedInvocationContext.FetchStrategyType.BLOOM_FILTER) {
			return false;
		}

		return true;
	}

	private boolean checkBloomFilter(CacheFetchContext context) {
		String membershipKey = String.valueOf(context.key());
		boolean mightExist = cachePenetration.mightContain(context.cacheName(), membershipKey);

		logDebug("Bloom filter check: cache={}, key={}, mightExist={}",
				context.cacheName(), membershipKey, mightExist);

		return mightExist;
	}

	private void updateBloomFilter(CacheFetchContext context) {
		try {
			String membershipKey = String.valueOf(context.key());
			cachePenetration.addIfEnabled(context.cacheName(), membershipKey);

			logDebug("Added key to bloom filter: cache={}, key={}",
					context.cacheName(), membershipKey);
		} catch (Exception e) {
			logDebug("Failed to update bloom filter: cache={}, key={}, error={}",
					context.cacheName(), context.key(), e.getMessage());
		}
	}
}