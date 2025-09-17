package com.david.spring.cache.redis.strategy.cacheable.impl;

import com.david.spring.cache.redis.cache.RedisProCache;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.strategy.cacheable.context.CacheGetContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 预刷新缓存获取策略
 * 在缓存即将过期时自动触发异步刷新，避免缓存雪崩
 *
 * @author David
 */
@Slf4j
@Component
public class PreRefreshCacheableStrategy extends AbstractCacheableStrategy {

	/** 预刷新阈值比例 */
	private static final double PRE_REFRESH_THRESHOLD = 0.2;

	@Override
	@Nullable
	public Cache.ValueWrapper get(@NonNull CacheGetContext<Object> context) {
		log.debug("Executing pre-refresh cache get strategy for key: {}", context.getKey());

		// 先获取基础缓存值（避免策略递归）
		Cache.ValueWrapper valueWrapper;
		if (context.getParentCache() instanceof RedisProCache rpc) {
			valueWrapper = rpc.getFromParent(context.getKey());
		} else {
			valueWrapper = context.getParentCache().get(context.getKey());
		}
		if (valueWrapper == null) {
			log.debug("Cache miss for key: {}", context.getKey());
			return null;
		}

		log.debug("Cache hit for key: {}", context.getKey());

		// 检查是否需要预刷新
		checkAndTriggerPreRefresh(context, valueWrapper);

		return valueWrapper;
	}

	@Override
	public boolean supports(@NonNull CacheGetContext<Object> context) {
		// 只有当支持预刷新且有缓存调用信息时才支持
		return context.supportsPreRefresh();
	}

	@Override
	public int getOrder() {
		// 高优先级，在基础策略之前执行
		return 100;
	}

	/**
	 * 检查并触发预刷新
	 *
	 * @param context      缓存上下文
	 * @param valueWrapper 当前缓存值
	 */
	private void checkAndTriggerPreRefresh(CacheGetContext<Object> context, Cache.ValueWrapper valueWrapper) {
		try {
			String cacheKey = context.createCacheKey();
			long ttl = context.getRedisTemplate().getExpire(cacheKey, TimeUnit.SECONDS);
			long configuredTtl = resolveConfiguredTtlSeconds(context, valueWrapper.get());

			log.debug("Pre-refresh check: name={}, redisKey={}, ttlSec={}, configuredTtlSec={}",
					context.getCacheName(), cacheKey, ttl, configuredTtl);

			if (ttl >= 0 && shouldPreRefresh(ttl, configuredTtl)) {
				triggerAsyncRefresh(context, cacheKey);
			}
		} catch (Exception e) {
			log.warn("Error during pre-refresh check for key: {}", context.getKey(), e);
		}
	}

	/**
	 * 判断是否应该预刷新
	 *
	 * @param currentTtl    当前TTL
	 * @param configuredTtl 配置的TTL
	 * @return 是否应该预刷新
	 */
	private boolean shouldPreRefresh(long currentTtl, long configuredTtl) {
		if (configuredTtl <= 0) {
			return false;
		}
		return currentTtl <= (configuredTtl * PRE_REFRESH_THRESHOLD);
	}

	/**
	 * 触发异步刷新
	 *
	 * @param context  缓存上下文
	 * @param cacheKey 缓存键
	 */
	private void triggerAsyncRefresh(CacheGetContext<Object> context, String cacheKey) {
		ReentrantLock lock = context.getRegistry().obtainLock(context.getCacheName(), context.getKey());

		context.getExecutor().execute(() -> {
			try {
				// 双重检查，避免不必要的刷新
				long ttl2 = context.getRedisTemplate().getExpire(cacheKey, TimeUnit.SECONDS);
				long configuredTtl2 = resolveConfiguredTtlSeconds(context, null);

				if (ttl2 < 0 || !shouldPreRefresh(ttl2, configuredTtl2)) {
					log.debug("Pre-refresh skipped after double-check for key: {}", context.getKey());
					return;
				}

				log.debug("Starting async pre-refresh for key: {}", context.getKey());

				// 执行实际的刷新逻辑
				CachedInvocation cachedInvocation = context.getCachedInvocation();
				if (cachedInvocation != null) {
					try {
						Object refreshed = cachedInvocation.invoke();
						boolean shouldCacheNull = false;
						if (cachedInvocation.getCachedInvocationContext() != null) {
							shouldCacheNull = cachedInvocation.getCachedInvocationContext().cacheNullValues();
						}
						if (refreshed != null || shouldCacheNull) {
							context.getParentCache().put(context.getKey(), refreshed);
							log.debug("Pre-refresh put new value for key: {} (nullCached={})", context.getKey(),
									refreshed == null);
						} else {
							log.debug("Pre-refresh result is null and null caching disabled for key: {}",
									context.getKey());
						}
						log.debug("Pre-refresh completed for key: {}", context.getKey());
					} catch (Exception e) {
						log.warn("Pre-refresh invocation failed for key: {}", context.getKey(), e);
					}
				}

			} catch (Exception e) {
				log.error("Error during async pre-refresh for key: {}", context.getKey(), e);
			} finally {
				lock.unlock();
			}
		});
	}

	/**
	 * 解析配置的TTL秒数
	 *
	 * @param context 缓存上下文
	 * @param value   缓存值
	 * @return TTL秒数
	 */
	private long resolveConfiguredTtlSeconds(CacheGetContext<Object> context, Object value) {
		try {
			CachedInvocation cachedInvocation = context.getCachedInvocation();
			if (cachedInvocation != null && cachedInvocation.getCachedInvocationContext() != null) {
				long ttl = cachedInvocation.getCachedInvocationContext().ttl();
				if (ttl > 0) {
					return ttl / 1000; // 转换为秒
				}
			}

			// 使用默认配置
			Duration defaultTtl = context.getCacheConfiguration().getTtl();
			return defaultTtl != null ? defaultTtl.getSeconds() : 3600; // 默认1小时

		} catch (Exception e) {
			log.warn("Error resolving TTL for key: {}", context.getKey(), e);
			return 3600; // 默认1小时
		}
	}
}
