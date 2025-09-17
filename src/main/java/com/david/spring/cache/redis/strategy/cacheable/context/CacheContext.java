package com.david.spring.cache.redis.strategy.cacheable.context;

import lombok.Builder;
import lombok.Data;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;

/**
 * 核心缓存上下文
 * 包含缓存操作的基本信息和依赖
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
	 *
	 * @return TTL秒数，如果配置为null则返回-1
	 */
	public long getConfiguredTtlSeconds() {
		return cacheConfiguration.getTtl() != null ?
				cacheConfiguration.getTtl().getSeconds() : -1;
	}
}