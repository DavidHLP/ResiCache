package com.david.spring.cache.redis.cache.support;

import com.david.spring.cache.redis.meta.CacheMata;
import com.david.spring.cache.redis.protection.CacheAvalanche;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 缓存操作服务
 * 提供通用的缓存操作方法，从RedisProCache中下沉而来
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheOperationService {

	private final CacheAvalanche cacheAvalanche;

	/**
	 * 判断是否需要预刷新
	 */
	public boolean shouldPreRefresh(long remainingTtlSec, long configuredTtlSec) {
		if (remainingTtlSec <= 0 || configuredTtlSec <= 0) {
			return false;
		}
		long threshold = Math.max(1L, (long) Math.floor(configuredTtlSec * 0.20d));
		return remainingTtlSec <= threshold;
	}

	/**
	 * 判断是否需要预刷新（支持自定义阈值）
	 */
	public boolean shouldPreRefresh(long remainingTtlSec, long configuredTtlSec, double threshold) {
		if (remainingTtlSec <= 0 || configuredTtlSec <= 0) {
			return false;
		}
		long thresholdTime = Math.max(1L, (long) Math.floor(configuredTtlSec * threshold));
		return remainingTtlSec <= thresholdTime;
	}

	/**
	 * 解析配置的TTL秒数
	 */
	public long resolveConfiguredTtlSeconds(@Nullable Object value, @NonNull Object key,
	                                        @Nullable RedisCacheConfiguration cacheConfiguration) {
		try {
			if (cacheConfiguration != null && value != null) {
				Duration d = cacheConfiguration.getTtlFunction().getTimeToLive(value, key);
				if (!d.isNegative() && !d.isZero()) {
					return d.getSeconds();
				}
			}
		} catch (Exception ignore) {
			// 忽略异常
		}
		return -1L;
	}

	/**
	 * 获取Redis中缓存的TTL
	 */
	public long getCacheTtl(String cacheKey, RedisTemplate<String, Object> redisTemplate) {
		try {
			return redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
		} catch (Exception e) {
			log.debug("Failed to get cache TTL: {}", e.getMessage());
			return -1;
		}
	}

	/**
	 * 执行缓存刷新
	 */
	public void doRefresh(CachedInvocation invocation, Object key, String cacheKey, long ttl,
	                      CacheRefreshCallback refreshCallback) {
		try {
			Object refreshed = invocation.invoke();
			refreshCallback.putCache(key, refreshed);
			log.info("Refreshed cache, name={}, redisKey={}, oldTtlSec={}, refreshedType={}",
					refreshCallback.getCacheName(), cacheKey, ttl,
					refreshed == null ? "null" : refreshed.getClass().getSimpleName());
		} catch (Throwable ex) {
			log.warn("Failed to refresh cache, name={}, redisKey={}, err={}",
					refreshCallback.getCacheName(), cacheKey, ex.getMessage());
		}
	}

	/**
	 * 包装缓存值（如果元信息不存在）
	 */
	public Object wrapIfMataAbsent(@Nullable Object value, @NonNull Object key,
	                               @Nullable RedisCacheConfiguration cacheConfiguration) {
		return wrapIfMataAbsent(value, key, cacheConfiguration, null);
	}

	/**
	 * 包装缓存值（支持上下文信息）
	 */
	public Object wrapIfMataAbsent(@Nullable Object value, @NonNull Object key,
	                               @Nullable RedisCacheConfiguration cacheConfiguration,
	                               @Nullable CachedInvocationContext invocationContext) {
		if (value == null) {
			return null;
		}
		if (value instanceof CacheMata) {
			return value;
		}

		long ttlSecs = resolveConfiguredTtlSeconds(value, key, cacheConfiguration);

		// 检查是否需要应用雪崩保护（只有在用户没有明确配置randomTtl时才应用）
		long effectiveTtl = shouldApplyAvalancheProtection(invocationContext, ttlSecs)
				? cacheAvalanche.jitterTtlSeconds(ttlSecs)
				: ttlSecs;

		// 不再计算本地过期时间，仅使用元信息中的 TTL，并由 Redis 统一管理过期
		return CacheMata.builder().ttl(effectiveTtl).value(value).build();
	}

	/**
	 * 判断是否应该应用雪崩保护
	 * 只有在用户没有明确配置 randomTtl 的情况下，才应用系统级别的雪崩保护
	 */
	private boolean shouldApplyAvalancheProtection(@Nullable CachedInvocationContext context, long ttlSecs) {
		if (ttlSecs <= 0) {
			return false; // TTL <= 0 不需要处理
		}

		if (context == null) {
			// 没有用户配置上下文，默认应用雪崩保护
			return true;
		}

		// 如果用户明确开启了 randomTtl，就不再应用系统级别的雪崩保护
		// 避免双重随机化
		if (context.randomTtl()) {
			log.debug("User enabled randomTtl, skipping system avalanche protection");
			return false;
		}

		// 如果用户明确配置了 fetchStrategy 为某些特定类型，可能有特殊的 TTL 处理需求
		if (context.fetchStrategy() != null) {
			return switch (context.fetchStrategy()) {
				case SIMPLE ->
					// SIMPLE 策略通常不需要系统级别的随机化
						false;
				case CUSTOM ->
					// 自定义策略可能有自己的 TTL 处理逻辑
						false;
				default ->
					// 其他策略默认应用雪崩保护
						true;
			};
		}

		// 默认情况下应用雪崩保护
		return true;
	}

	/**
	 * 从存储值中解包缓存值
	 */
	public Object fromStoreValue(@Nullable Object storeValue) {
		if (storeValue instanceof CacheMata) {
			return ((CacheMata) storeValue).getValue();
		}
		return storeValue;
	}

	/**
	 * 应用抖动后的过期时间
	 */
	public void applyLitteredExpire(Object ignoredKey, Object toStore, String cacheKey,
	                                RedisTemplate<String, Object> redisTemplate) {
		try {
			if (toStore instanceof CacheMata meta && meta.getTtl() > 0) {
				long seconds = meta.getTtl();
				redisTemplate.expire(cacheKey, seconds, TimeUnit.SECONDS);
			}
		} catch (Exception ignore) {
			// 忽略异常
		}
	}

	/**
	 * 判断 putIfAbsent 前该 key 是否不存在
	 */
	public boolean wasKeyAbsentBeforePut(String cacheKey, RedisTemplate<String, Object> redisTemplate) {
		try {
			Long ttl = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
			return ttl == -2L; // -2 表示 Redis 中 key 不存在
		} catch (Exception ignore) {
			return false;
		}
	}


	/**
	 * 缓存刷新回调接口
	 */
	public interface CacheRefreshCallback {
		void putCache(Object key, Object value);

		String getCacheName();
	}
}