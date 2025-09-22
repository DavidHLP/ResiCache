package com.david.spring.cache.redis.cache.support;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.strategy.CacheFetchStrategy;
import com.david.spring.cache.redis.strategy.support.StrategyValidationUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.stereotype.Component;

/**
 * 缓存上下文验证器
 * 专门负责缓存上下文的验证逻辑
 */
@Slf4j
@Component
public class CacheContextValidator {

	/**
	 * 验证缓存调用上下文
	 */
	public boolean isValidInvocationContext(CachedInvocationContext context) {
		return StrategyValidationUtils.validateAllRequirements(context);
	}

	/**
	 * 验证获取上下文
	 */
	public boolean isValidFetchContext(CacheFetchStrategy.CacheFetchContext context) {
		return context != null;
	}

	/**
	 * 判断是否应该执行策略
	 */
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
}