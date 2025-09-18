package com.david.spring.cache.redis.strategy.cacheable.context;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import lombok.Builder;
import lombok.Data;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 核心缓存上下文
 * 集成CachedInvocationContext提供更细粒度的上下文管理
 *
 * @author David
 */
@Data
@Builder
public class CacheContext {

	/** 缓存键 */
	@NonNull
	private final Object key;

	/** 缓存名称 */
	@NonNull
	private final String cacheName;

	/** 父缓存实例 */
	@NonNull
	private final Cache parentCache;

	/** Redis 模板 */
	@NonNull
	private final RedisTemplate<String, Object> redisTemplate;

	/** 缓存配置 */
	@NonNull
	private final RedisCacheConfiguration cacheConfiguration;

	/** 调用上下文，提供详细的缓存配置信息 */
	@Nullable
	private final CachedInvocationContext invocationContext;

	/**
	 * 创建完整的Redis缓存键
	 *
	 * @return Redis缓存键字符串
	 */
	public String createRedisKey() {
		return cacheName + "::" + key;
	}

	/**
	 * 获取缓存的TTL（秒）
	 * 优先使用调用上下文中的TTL配置，否则使用缓存配置中的TTL
	 *
	 * @return TTL秒数，如果配置为null或无效则返回-1
	 */
	public long getConfiguredTtlSeconds() {
		// 优先使用调用上下文中的TTL
		if (invocationContext != null && invocationContext.ttl() > 0) {
			return Math.max(0L, invocationContext.ttl() / 1000);
		}

		// 回退到缓存配置中的TTL
		return cacheConfiguration.getTtl() != null ?
				cacheConfiguration.getTtl().getSeconds() : -1;
	}

	/**
	 * 获取带抖动的有效TTL（秒）
	 * 根据调用上下文的配置应用TTL抖动，避免缓存雪崩
	 *
	 * @return 应用抖动后的TTL秒数
	 */
	public long getEffectiveTtlSeconds() {
		long baseTtl = getConfiguredTtlSeconds();
		if (baseTtl <= 0) {
			return baseTtl;
		}

		// 检查是否启用随机TTL
		if (invocationContext != null && invocationContext.randomTtl()) {
			float variance = invocationContext.variance();
			if (variance > 0.0f && variance <= 1.0f) {
				double ratio = ThreadLocalRandom.current().nextDouble(0.0d, variance);
				long jittered = (long) Math.floor(baseTtl * (1.0d - ratio));
				return Math.max(1L, jittered);
			}
		}

		return baseTtl;
	}

	/**
	 * 获取动态TTL
	 * 根据值和键使用TTL函数计算动态TTL
	 *
	 * @param value 缓存值
	 * @return 动态TTL秒数，如果无法计算则返回配置的TTL
	 */
	public long getDynamicTtlSeconds(@Nullable Object value) {
		try {
			if (value != null && cacheConfiguration.getTtlFunction() != null) {
				Duration dynamicTtl = cacheConfiguration.getTtlFunction().getTimeToLive(value, key);
				if (dynamicTtl != null && !dynamicTtl.isNegative() && !dynamicTtl.isZero()) {
					return dynamicTtl.getSeconds();
				}
			}
		} catch (Exception ignored) {
			// 忽略异常，使用默认TTL
		}

		return getConfiguredTtlSeconds();
	}

	/**
	 * 检查是否应该缓存null值
	 *
	 * @return 如果应该缓存null值则返回true
	 */
	public boolean shouldCacheNullValues() {
		// 优先使用调用上下文的配置
		if (invocationContext != null) {
			return invocationContext.cacheNullValues();
		}

		// 回退到缓存配置
		return cacheConfiguration.getAllowCacheNullValues();
	}

	/**
	 * 检查是否启用同步模式
	 *
	 * @return 如果启用同步模式则返回true
	 */
	public boolean isSyncEnabled() {
		return invocationContext != null && invocationContext.isSync();
	}

	/**
	 * 检查是否使用二级缓存
	 *
	 * @return 如果使用二级缓存则返回true
	 */
	public boolean useSecondLevelCache() {
		return invocationContext != null && invocationContext.useSecondLevelCache();
	}

	/**
	 * 获取缓存键生成器名称
	 *
	 * @return 键生成器名称，可能为null
	 */
	@Nullable
	public String getKeyGenerator() {
		return invocationContext != null ? invocationContext.getKeyGenerator() : null;
	}

	/**
	 * 获取缓存管理器名称
	 *
	 * @return 缓存管理器名称，可能为null
	 */
	@Nullable
	public String getCacheManager() {
		return invocationContext != null ? invocationContext.getCacheManager() : null;
	}

	/**
	 * 获取缓存解析器名称
	 *
	 * @return 缓存解析器名称，可能为null
	 */
	@Nullable
	public String getCacheResolver() {
		return invocationContext != null ? invocationContext.getCacheResolver() : null;
	}

	/**
	 * 获取条件表达式
	 *
	 * @return 条件表达式，可能为null
	 */
	@Nullable
	public String getCondition() {
		return invocationContext != null ? invocationContext.getCondition() : null;
	}

	/**
	 * 获取unless表达式
	 *
	 * @return unless表达式，可能为null
	 */
	@Nullable
	public String getUnless() {
		return invocationContext != null ? invocationContext.unless() : null;
	}

	/**
	 * 检查是否有有效的调用上下文
	 *
	 * @return 如果有有效的调用上下文则返回true
	 */
	public boolean hasInvocationContext() {
		return invocationContext != null;
	}

	/**
	 * 检查是否需要保护机制
	 *
	 * @return 如果需要保护机制则返回true
	 */
	public boolean needsProtection() {
		return invocationContext != null && invocationContext.needsProtection();
	}

	/**
	 * 检查是否启用分布式锁
	 *
	 * @return 如果启用分布式锁则返回true
	 */
	public boolean useDistributedLock() {
		return invocationContext != null && invocationContext.distributedLock();
	}

	/**
	 * 检查是否启用内部锁
	 *
	 * @return 如果启用内部锁则返回true
	 */
	public boolean useInternalLock() {
		return invocationContext != null && invocationContext.internalLock();
	}

	/**
	 * 检查是否使用布隆过滤器
	 *
	 * @return 如果使用布隆过滤器则返回true
	 */
	public boolean useBloomFilter() {
		return invocationContext != null && invocationContext.useBloomFilter();
	}

	/**
	 * 获取分布式锁名称
	 *
	 * @return 分布式锁名称，可能为null
	 */
	@Nullable
	public String getDistributedLockName() {
		return invocationContext != null ? invocationContext.distributedLockName() : null;
	}
}