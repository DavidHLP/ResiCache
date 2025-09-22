package com.david.spring.cache.redis.chain.impl;

import com.david.spring.cache.redis.protection.CachePenetration;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.RegistryFactory;
import com.david.spring.cache.redis.chain.AbstractCacheFetchStrategy;
import com.david.spring.cache.redis.cache.support.CacheOperationService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * 布隆过滤器策略。
 * <p>
 * 使用布隆过滤器防止缓存穿透攻击。在缓存未命中时，先检查布隆过滤器以避免无效的数据库查询。
 * </p>
 *
 * @author CacheGuard
 * @since 1.0.0
 */
@Slf4j
@Component
public class BloomFilterStrategy extends AbstractCacheFetchStrategy {

	/** 布隆过滤器服务，用于判断键是否可能存在 */
	private final CachePenetration cachePenetration;

	public BloomFilterStrategy(RegistryFactory registryFactory,
	                           @Qualifier("cacheRefreshExecutor") Executor executor,
	                           CacheOperationService cacheOperationService,
	                           CachePenetration cachePenetration) {
		super(registryFactory, executor, cacheOperationService);
		this.cachePenetration = cachePenetration;
	}

	@Override
	@Nullable
	public ValueWrapper fetch(@Nonnull CacheFetchContext context) {
		if (isValidContext(context)) {
			logDebug("Invalid context, skipping bloom filter check");
			return context.valueWrapper();
		}

		if (!context.invocationContext().useBloomFilter()) {
			logDebug("Bloom filter disabled, skipping check");
			return context.valueWrapper();
		}

		return executeWithMonitoring("bloom-filter", context, () -> {
			CachedInvocationContext invocationContext = context.invocationContext();
			boolean hasValue = context.hasValue();

			if (!hasValue) {
				// 缓存未命中，检查布隆过滤器
				return handleCacheMiss(context, invocationContext);
			} else {
				// 缓存命中，更新布隆过滤器
				return handleCacheHit(context, invocationContext);
			}
		});
	}

	/**
	 * 处理缓存未命中场景。
	 */
	@Nullable
	private ValueWrapper handleCacheMiss(@Nonnull CacheFetchContext context,
	                                     @Nonnull CachedInvocationContext invocationContext) {
		boolean mightExist = checkBloomFilter(context);

		if (!mightExist) {
			logDebug("Bloom filter blocked non-existent key: cache={}, key={}",
					context.cacheName(), context.key());
			if (invocationContext.cacheNullValues()) {
				logDebug("Bloom filter blocking despite cacheNullValues=true: cache={}, key={}",
						context.cacheName(), context.key());
			}
			return null;
		}

		logDebug("Bloom filter passed for key: cache={}, key={}", context.cacheName(), context.key());
		return context.valueWrapper();
	}

	/**
	 * 处理缓存命中场景。
	 */
	@Nullable
	private ValueWrapper handleCacheHit(@Nonnull CacheFetchContext context,
	                                    @Nonnull CachedInvocationContext invocationContext) {
		Object value = context.getValue();
		boolean shouldUpdate = value != null || invocationContext.cacheNullValues();

		if (shouldUpdate) {
			updateBloomFilter(context);
		}

		return context.valueWrapper();
	}

	@Override
	public boolean supports(@Nonnull CachedInvocationContext invocationContext) {
		return invocationContext.useBloomFilter()
				|| invocationContext.fetchStrategy() == CachedInvocationContext.FetchStrategyType.BLOOM_FILTER;
	}

	@Override
	public int getOrder() {
		return 5;
	}

	@Override
	public boolean shouldStopOnNull() {
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