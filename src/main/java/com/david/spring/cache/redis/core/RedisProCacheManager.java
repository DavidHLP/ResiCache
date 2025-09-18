package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.cache.RedisProCache;
import com.david.spring.cache.redis.lock.DistributedLock;
import com.david.spring.cache.redis.strategy.CacheFetchStrategyManager;
import com.david.spring.cache.redis.strategy.CacheOperationService;
import com.david.spring.cache.redis.protection.CacheAvalanche;
import com.david.spring.cache.redis.protection.CacheBreakdown;
import com.david.spring.cache.redis.protection.CachePenetration;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import com.david.spring.cache.redis.registry.EvictInvocationRegistry;
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
	private final Map<String, RedisCacheConfiguration> initialCacheConfigurations;
	private final RedisTemplate<String, Object> redisTemplate;
	private final RedisCacheWriter cacheWriter;
	private final CacheInvocationRegistry registry;
	private final EvictInvocationRegistry evictRegistry;
	private final Executor executor;
	@Getter
	private final RedisCacheConfiguration redisCacheConfiguration;
	private final DistributedLock distributedLock;
	private final CachePenetration cachePenetration;
	private final CacheBreakdown cacheBreakdown;
	private final CacheFetchStrategyManager strategyManager;
	private final CacheOperationService cacheOperationService;

	public RedisProCacheManager(
			RedisCacheWriter cacheWriter,
			Map<String, RedisCacheConfiguration> initialCacheConfigurations,
			RedisTemplate<String, Object> cacheRedisTemplate,
			RedisCacheConfiguration redisCacheConfiguration,
			CacheInvocationRegistry registry,
			EvictInvocationRegistry evictRegistry,
			Executor executor,
			DistributedLock distributedLock,
			CachePenetration cachePenetration,
			CacheBreakdown cacheBreakdown,
			CacheFetchStrategyManager strategyManager,
			CacheOperationService cacheOperationService) {
		super(cacheWriter, redisCacheConfiguration, initialCacheConfigurations);
		this.initialCacheConfigurations = initialCacheConfigurations;
		this.redisTemplate = cacheRedisTemplate;
		this.cacheWriter = cacheWriter;
		this.redisCacheConfiguration = redisCacheConfiguration;
		this.registry = registry;
		this.evictRegistry = evictRegistry;
		this.executor = executor;
		this.distributedLock = distributedLock;
		this.cachePenetration = cachePenetration;
		this.cacheBreakdown = cacheBreakdown;
		this.strategyManager = strategyManager;
		this.cacheOperationService = cacheOperationService;
	}

	@Override
	@Nonnull
	protected Collection<RedisCache> loadCaches() {
		List<RedisCache> caches = new LinkedList<>();
		for (Map.Entry<String, RedisCacheConfiguration> entry :
				getInitialCacheConfigurations().entrySet()) {
			caches.add(createRedisCache(entry.getKey(), entry.getValue()));
		}
		return caches;
	}

	@Override
	@Nonnull
	public RedisCache createRedisCache(
			@NonNull String name, @Nullable RedisCacheConfiguration cacheConfig) {
		RedisCacheConfiguration config =
				getInitialCacheConfigurations().getOrDefault(name, redisCacheConfiguration);

		return new RedisProCache(
				name,
				cacheWriter,
				config,
				redisTemplate,
				registry,
				evictRegistry,
				executor,
				distributedLock,
				cachePenetration,
				cacheBreakdown,
				strategyManager,
				cacheOperationService);
	}

	public void initializeCaches() {
		super.initializeCaches(); // 重新加载 caches
	}
}
