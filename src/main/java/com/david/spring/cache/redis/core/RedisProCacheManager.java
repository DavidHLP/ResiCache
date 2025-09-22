package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.cache.RedisProCache;
import com.david.spring.cache.redis.cache.support.CacheContextValidator;
import com.david.spring.cache.redis.cache.support.CacheStrategyExecutor;
import com.david.spring.cache.redis.config.CacheGuardProperties;
import com.david.spring.cache.redis.lock.DistributedLock;
import com.david.spring.cache.redis.protection.CacheBreakdown;
import com.david.spring.cache.redis.protection.CachePenetration;
import com.david.spring.cache.redis.registry.factory.RegistryFactory;
import com.david.spring.cache.redis.strategy.CacheFetchStrategyManager;
import com.david.spring.cache.redis.cache.support.CacheOperationService;
import jakarta.annotation.Nonnull;
import lombok.Getter;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

public class RedisProCacheManager extends RedisCacheManager {

	@Getter
	private final Map<String, RedisCacheConfiguration> redisCacheConfigurationMap;
	private final RedisTemplate<String, Object> redisTemplate;
	private final RedisCacheWriter cacheWriter;
	private final RegistryFactory registryFactory;
	private final Executor executor;
	@Getter
	private final RedisCacheConfiguration redisCacheConfiguration;
	private final DistributedLock distributedLock;
	private final CachePenetration cachePenetration;
	private final CacheBreakdown cacheBreakdown;
	private final CacheFetchStrategyManager strategyManager;
	private final CacheOperationService cacheOperationService;
	private final CacheContextValidator contextValidator;
	private final CacheStrategyExecutor strategyExecutor;
	private final CacheGuardProperties properties;

	public RedisProCacheManager(
			RedisCacheWriter cacheWriter,
			Map<String, RedisCacheConfiguration> redisCacheConfigurationMap,
			RedisTemplate<String, Object> cacheRedisTemplate,
			RedisCacheConfiguration redisCacheConfiguration,
			RegistryFactory registryFactory,
			Executor executor,
			DistributedLock distributedLock,
			CachePenetration cachePenetration,
			CacheBreakdown cacheBreakdown,
			CacheFetchStrategyManager strategyManager,
			CacheOperationService cacheOperationService,
			CacheContextValidator contextValidator,
			CacheStrategyExecutor strategyExecutor,
			CacheGuardProperties properties) {
		super(cacheWriter, redisCacheConfiguration, redisCacheConfigurationMap);
		this.redisCacheConfigurationMap = redisCacheConfigurationMap;
		this.redisTemplate = cacheRedisTemplate;
		this.cacheWriter = cacheWriter;
		this.redisCacheConfiguration = redisCacheConfiguration;
		this.registryFactory = registryFactory;
		this.executor = executor;
		this.distributedLock = distributedLock;
		this.cachePenetration = cachePenetration;
		this.cacheBreakdown = cacheBreakdown;
		this.strategyManager = strategyManager;
		this.cacheOperationService = cacheOperationService;
		this.contextValidator = contextValidator;
		this.strategyExecutor = strategyExecutor;
		this.properties = properties;
	}

	@Override
	@Nonnull
	protected Collection<RedisCache> loadCaches() {
		List<RedisCache> caches = new LinkedList<>();
		for (Map.Entry<String, RedisCacheConfiguration> entry :
				getRedisCacheConfigurationMap().entrySet()) {
			caches.add(createRedisCache(entry.getKey(), entry.getValue()));
		}
		return caches;
	}

	@Override
	@Nonnull
	public RedisCache createRedisCache(
			@NonNull String name, @Nullable RedisCacheConfiguration cacheConfig) {
		RedisCacheConfiguration config =
				getRedisCacheConfigurationMap().getOrDefault(name, redisCacheConfiguration);

		return new RedisProCache(
				name,
				cacheWriter,
				config,
				redisTemplate,
				registryFactory,
				executor,
				distributedLock,
				cachePenetration,
				cacheBreakdown,
				strategyManager,
				cacheOperationService,
				contextValidator,
				strategyExecutor,
				properties);
	}

	public void initializeCaches() {
		super.initializeCaches(); // 重新加载 caches
	}
}
