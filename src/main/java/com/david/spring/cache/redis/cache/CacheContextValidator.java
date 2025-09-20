package com.david.spring.cache.redis.cache;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.strategy.CacheFetchStrategy;
import com.david.spring.cache.redis.strategy.CacheFetchStrategyManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;

@Slf4j
public record CacheContextValidator(CacheFetchStrategyManager strategyManager) {

	public boolean isValidInvocationContext(CachedInvocationContext context) {
		if (context == null) {
			return false;
		}

		if (!validateBasicProperties(context)) {
			return false;
		}

		if (!validateNumericRanges(context)) {
			return false;
		}

		if (!validateLogicalConsistency(context)) {
			return false;
		}

		validateLockConfiguration(context);

		return true;
	}

	public boolean isValidFetchContext(CacheFetchStrategy.CacheFetchContext context) {
		return context != null
				&& context.cacheName() != null
				&& context.key() != null
				&& context.cacheKey() != null
				&& context.redisTemplate() != null
				&& context.callback() != null;
	}

	public CachedInvocationContext createDefaultContext(String cacheName) {
		return CachedInvocationContext.builder()
				.cacheNames(new String[]{cacheName})
				.key("default")
				.condition("")
				.sync(false)
				.value(new String[]{})
				.keyGenerator("")
				.cacheManager("")
				.cacheResolver("")
				.unless("")
				.ttl(0L)
				.type(Object.class)
				.useSecondLevelCache(false)
				.distributedLock(false)
				.distributedLockName("")
				.internalLock(false)
				.cacheNullValues(false)
				.useBloomFilter(false)
				.randomTtl(false)
				.variance(0.0f)
				.fetchStrategy(CachedInvocationContext.FetchStrategyType.AUTO)
				.enablePreRefresh(false)
				.preRefreshThreshold(0.3)
				.customStrategyClass("")
				.build();
	}

	public boolean shouldExecuteStrategies(CachedInvocationContext invocationContext,
	                                       Cache.ValueWrapper baseValue) {
		if (invocationContext.fetchStrategy() == CachedInvocationContext.FetchStrategyType.SIMPLE) {
			return baseValue == null || invocationContext.cacheNullValues();
		}

		if (invocationContext.useBloomFilter()) {
			return true;
		}

		if (invocationContext.enablePreRefresh() && baseValue != null) {
			return true;
		}

		if (baseValue == null) {
			return true;
		}

		return invocationContext.distributedLock() || invocationContext.internalLock();
	}

	private boolean validateBasicProperties(CachedInvocationContext context) {
		if (context.cacheNames() == null || context.cacheNames().length == 0) {
			log.debug("Invalid context: missing cache names");
			return false;
		}

		if (context.fetchStrategy() == null) {
			log.debug("Invalid context: missing fetch strategy");
			return false;
		}
		return true;
	}

	private boolean validateNumericRanges(CachedInvocationContext context) {
		if (context.variance() < 0 || context.variance() > 1) {
			log.debug("Invalid context: variance {} out of range [0,1]", context.variance());
			return false;
		}

		if (context.preRefreshThreshold() < 0 || context.preRefreshThreshold() > 1) {
			log.debug("Invalid context: preRefreshThreshold {} out of range [0,1]", context.preRefreshThreshold());
			return false;
		}
		return true;
	}

	private boolean validateLogicalConsistency(CachedInvocationContext context) {
		if (context.enablePreRefresh() && context.ttl() <= 0) {
			log.debug("Invalid context: preRefresh enabled but TTL <= 0");
			return false;
		}

		if (context.randomTtl() && context.variance() <= 0) {
			log.debug("Invalid context: randomTtl enabled but variance <= 0");
			return false;
		}
		return true;
	}

	private void validateLockConfiguration(CachedInvocationContext context) {
		if (context.distributedLock() &&
				(context.distributedLockName() == null || context.distributedLockName().trim().isEmpty())) {
			log.debug("Invalid context: distributedLock enabled but no lock name specified");
		}
	}
}