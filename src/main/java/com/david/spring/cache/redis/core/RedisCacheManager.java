package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.factory.CacheCreationConfig;
import com.david.spring.cache.redis.factory.CacheFactoryRegistry;
import com.david.spring.cache.redis.factory.CacheType;
import com.david.spring.cache.redis.template.CacheOperationTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.transaction.AbstractTransactionSupportingCacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RedisCacheManager extends AbstractTransactionSupportingCacheManager {

	private final RedisTemplate<String, Object> redisTemplate;
	private final Map<String, Duration> cacheConfigurations;
	private final Duration defaultTtl;
	private final boolean allowNullValues;
	private final Map<String, Cache> cacheMap = new ConcurrentHashMap<>();
	private final Map<String, CacheType> cacheTypes = new ConcurrentHashMap<>();
	// 工厂模式支持
	private CacheFactoryRegistry factoryRegistry;
	// 缓存操作模板支持
	private CacheOperationTemplate operationTemplate;
	// 默认缓存类型
	private CacheType defaultCacheType = CacheType.REDIS;

	public RedisCacheManager(RedisTemplate<String, Object> redisTemplate) {
		this(redisTemplate, Duration.ofMinutes(60), true, Collections.emptyMap());
	}

	public RedisCacheManager(RedisTemplate<String, Object> redisTemplate,
	                         Duration defaultTtl,
	                         boolean allowNullValues,
	                         Map<String, Duration> cacheConfigurations) {
		this(redisTemplate, defaultTtl, allowNullValues, cacheConfigurations, CacheType.REDIS);
	}

	public RedisCacheManager(RedisTemplate<String, Object> redisTemplate,
	                         Duration defaultTtl,
	                         boolean allowNullValues,
	                         Map<String, Duration> cacheConfigurations,
	                         CacheType defaultCacheType) {
		this.redisTemplate = redisTemplate;
		this.defaultTtl = defaultTtl;
		this.allowNullValues = allowNullValues;
		this.cacheConfigurations = new ConcurrentHashMap<>(cacheConfigurations);
		this.defaultCacheType = defaultCacheType;
	}

	@Override
	@NonNull
	protected Collection<? extends Cache> loadCaches() {
		return Collections.emptyList();
	}

	@Override
	@NonNull
	protected Cache getMissingCache(@NonNull String name) {
		return cacheMap.computeIfAbsent(name, this::createRedisCache);
	}

	private Cache createRedisCache(String name) {
		Duration ttl = cacheConfigurations.getOrDefault(name, defaultTtl);

		// 检查是否有工厂注册表，如果有则使用工厂模式
		if (factoryRegistry != null) {
			CacheType cacheType = cacheTypes.getOrDefault(name, defaultCacheType);

			CacheCreationConfig config = CacheCreationConfig.builder()
					.cacheName(name)
					.cacheType(cacheType)
					.redisTemplate(redisTemplate)
					.defaultTtl(ttl)
					.allowNullValues(allowNullValues)
					.enableStatistics(true)
					.build();

			log.debug("Creating cache '{}' using factory pattern with type: {}", name, cacheType);
			return factoryRegistry.createCache(config);
		}

		// 回退到直接创建Redis缓存
		log.debug("Creating Redis cache '{}' with TTL: {} (direct creation)", name, ttl);
		RedisCache redisCache = new RedisCache(name, redisTemplate, ttl, allowNullValues);
		// 注入模板支持
		if (operationTemplate != null) {
			redisCache.setOperationTemplate(operationTemplate);
		}
		return redisCache;
	}

	public void setCacheConfiguration(String cacheName, Duration ttl) {
		cacheConfigurations.put(cacheName, ttl);
		cacheMap.remove(cacheName);
	}

	public Duration getCacheTtl(String cacheName) {
		return cacheConfigurations.getOrDefault(cacheName, defaultTtl);
	}

	/**
	 * 设置工厂注册表（支持工厂模式）
	 */
	public void setCacheFactoryRegistry(CacheFactoryRegistry factoryRegistry) {
		this.factoryRegistry = factoryRegistry;
		if (factoryRegistry != null) {
			log.info("Cache factory registry configured with {} factories", factoryRegistry.getFactoryCount());
			log.debug("Supported cache types: {}", factoryRegistry.getSupportedCacheTypes());
		}
	}

	/**
	 * 设置默认缓存类型
	 */
	public void setDefaultCacheType(CacheType defaultCacheType) {
		this.defaultCacheType = defaultCacheType;
		log.info("Default cache type set to: {}", defaultCacheType);
	}

	/**
	 * 设置特定缓存的类型
	 */
	public void setCacheType(String cacheName, CacheType cacheType) {
		cacheTypes.put(cacheName, cacheType);
		cacheMap.remove(cacheName);  // 移除已创建的缓存，强制重新创建
		log.debug("Set cache type for '{}': {}", cacheName, cacheType);
	}

	/**
	 * 设置缓存操作模板（支持模板方法模式）
	 */
	public void setOperationTemplate(CacheOperationTemplate operationTemplate) {
		this.operationTemplate = operationTemplate;
		log.info("Cache operation template configured: {}", operationTemplate.getClass().getSimpleName());
		// 更新已创建的缓存
		cacheMap.values().forEach(cache -> {
			if (cache instanceof RedisCache redisCache) {
				redisCache.setOperationTemplate(operationTemplate);
			}
		});
	}

	@Override
	public void afterPropertiesSet() {
		super.afterPropertiesSet();
		log.info("RedisCacheManager initialized with default TTL: {}, allowNullValues: {}",
				defaultTtl, allowNullValues);
	}
}