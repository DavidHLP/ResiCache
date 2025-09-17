package com.david.spring.cache.redis.strategy.cacheable.impl;

import com.david.spring.cache.redis.cache.RedisProCache;
import com.david.spring.cache.redis.strategy.cacheable.context.CacheGetContext;
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

	@Override
	@Nullable
	public Cache.ValueWrapper get(@NonNull CacheGetContext<Object> context) {
		log.debug("Executing basic cache get strategy for key: {}", context.getKey());

		try {
			// 避免循环调用，直接调用 RedisProCache 的父类方法
			Cache parentCache = context.getParentCache();

			if (parentCache instanceof RedisProCache redisProCache) {
				Cache.ValueWrapper valueWrapper = redisProCache.getFromParent(context.getKey());

				if (valueWrapper == null) {
					log.debug("Cache miss for key: {}", context.getKey());
					return null;
				}

				log.debug("Cache hit for key: {}", context.getKey());
				return valueWrapper;
			}

			// 对于其他类型的缓存，直接调用
			Cache.ValueWrapper valueWrapper = parentCache.get(context.getKey());
			if (valueWrapper == null) {
				log.debug("Cache miss for key: {}", context.getKey());
				return null;
			}

			log.debug("Cache hit for key: {}", context.getKey());
			return valueWrapper;

		} catch (Exception e) {
			log.error("Error during basic cache get for key: {}", context.getKey(), e);
			return null;
		}
	}

	@Override
	public boolean supports(@NonNull CacheGetContext<Object> context) {
		// 基础策略支持所有情况（作为兜底策略）
		return true;
	}

	@Override
	public int getOrder() {
		// 最低优先级，作为兜底策略
		return Integer.MAX_VALUE;
	}
}
