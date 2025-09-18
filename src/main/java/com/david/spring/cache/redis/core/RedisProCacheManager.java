package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.cache.RedisProCache;
import com.david.spring.cache.redis.registry.impl.CacheInvocationRegistry;
import com.david.spring.cache.redis.strategy.cacheable.CacheableStrategyManager;
import jakarta.annotation.Nonnull;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Slf4j
public class RedisProCacheManager extends RedisCacheManager {

	@Getter
	private final Map<String, RedisCacheConfiguration> initialCacheConfigurations;
	private final RedisCacheWriter cacheWriter;
	@Getter
	private final RedisCacheConfiguration redisCacheConfiguration;
	private final CacheableStrategyManager strategyManager;
	private final CacheInvocationRegistry invocationRegistry;

	public RedisProCacheManager(
			RedisCacheWriter cacheWriter,
			Map<String, RedisCacheConfiguration> initialCacheConfigurations,
			RedisCacheConfiguration redisCacheConfiguration,
			CacheableStrategyManager strategyManager,
			CacheInvocationRegistry invocationRegistry){
		super(cacheWriter, redisCacheConfiguration, initialCacheConfigurations);
		log.info("Initializing RedisProCacheManager with {} initial cache configurations",
				initialCacheConfigurations.size());
		this.initialCacheConfigurations = initialCacheConfigurations;
		this.cacheWriter = cacheWriter;
		this.redisCacheConfiguration = redisCacheConfiguration;
		this.strategyManager = strategyManager;
		this.invocationRegistry = invocationRegistry;
		log.debug("RedisProCacheManager successfully initialized with protection mechanisms, strategy manager, and processor manager");
	}

	@Override
	@Nonnull
	protected Collection<RedisCache> loadCaches() {
		log.info("Loading Redis caches for {} cache configurations", getInitialCacheConfigurations().size());
		List<RedisCache> caches = new LinkedList<>();
		for (Map.Entry<String, RedisCacheConfiguration> entry : getInitialCacheConfigurations().entrySet()) {
			String cacheName = entry.getKey();
			log.debug("Loading cache: {}", cacheName);
			caches.add(createRedisCache(cacheName, entry.getValue()));
		}
		log.info("Successfully loaded {} Redis caches", caches.size());
		return caches;
	}

	@Override
	@Nonnull
	public RedisCache createRedisCache(
			@NonNull String name, @Nullable RedisCacheConfiguration cacheConfig) {
		log.debug("Creating Redis cache: {}", name);
		RedisCacheConfiguration config = getInitialCacheConfigurations().getOrDefault(name, redisCacheConfiguration);

		RedisProCache cache = new RedisProCache(
				name,
				cacheWriter,
				config,
				strategyManager,
				invocationRegistry);
		log.debug("Successfully created Redis cache: {} with TTL: {}", name,
				config.getTtl() != null ? config.getTtl().getSeconds() + "s" : "default");
		return cache;
	}

	public void initializeCaches() {
		log.info("Initializing Redis caches");
		super.initializeCaches();
		log.debug("Redis caches initialization completed");
	}
}
