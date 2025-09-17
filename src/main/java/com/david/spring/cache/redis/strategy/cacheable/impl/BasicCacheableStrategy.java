package com.david.spring.cache.redis.strategy.cacheable.impl;

import com.david.spring.cache.redis.strategy.cacheable.context.CacheableContext;
import com.david.spring.cache.redis.strategy.cacheable.support.CacheOperationSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 基础缓存获取策略
 * 提供最基本的缓存获取功能，不包含额外的保护机制
 *
 * @author David
 */
@Slf4j
@Component
public class BasicCacheableStrategy extends AbstractCacheableStrategy {

	public BasicCacheableStrategy(CacheOperationSupport cacheOperationSupport) {
		super(cacheOperationSupport);
	}

	@Override
	@Nullable
	public Cache.ValueWrapper get(@NonNull CacheableContext<Object> context) {
		log.debug("Executing basic cache get strategy for key: {}", context.getKey());

		Cache.ValueWrapper valueWrapper = cacheOperationSupport.safeGet(context);
		if (valueWrapper != null) {
			log.debug("Cache hit for key: {}", context.getKey());
		} else {
			log.debug("Cache miss for key: {}", context.getKey());
		}

		return valueWrapper;
	}

	@Override
	public boolean supports(@NonNull CacheableContext<Object> context) {
		// 基础策略支持所有情况（作为兜底策略）
		return true;
	}

	@Override
	public int getOrder() {
		// 最低优先级，作为兜底策略
		return Integer.MAX_VALUE;
	}
}
