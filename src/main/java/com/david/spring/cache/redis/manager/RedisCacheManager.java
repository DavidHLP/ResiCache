package com.david.spring.cache.redis.manager;

import com.david.spring.cache.redis.core.RedisCache;
import com.david.spring.cache.redis.template.CacheOperationTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.cache.CacheType;
import org.springframework.cache.Cache;
import org.springframework.cache.transaction.AbstractTransactionSupportingCacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.david.spring.cache.redis.core.CacheConstants.CacheLayers;
import static com.david.spring.cache.redis.core.CacheConstants.Operations;

@Slf4j
public class RedisCacheManager extends AbstractTransactionSupportingCacheManager {

	private final RedisTemplate<String, Object> redisTemplate;
	private final Map<String, Duration> cacheConfigurations;
	private final Duration defaultTtl;
	private final boolean allowNullValues;
	private final Map<String, Cache> cacheMap = new ConcurrentHashMap<>();
	// 事件支持（通过抽象类）
	private final AbstractEventAwareCache eventSupport = new AbstractEventAwareCache() {};
	// 默认缓存类型
	private final CacheType defaultCacheType;
	// 缓存操作模板支持
	private CacheOperationTemplate operationTemplate;

	public RedisCacheManager(RedisTemplate<String, Object> redisTemplate) {
		this(redisTemplate, Duration.ofMinutes(60), true, Collections.emptyMap(), CacheType.REDIS);
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
		CacheType cacheType = defaultCacheType;

		log.debug("Creating cache '{}' with type: {}, TTL: {}", name, cacheType, ttl);

		// 发布缓存创建开始事件
		eventSupport.publishOperationStartEvent(name, name, CacheLayers.CACHE_MANAGER, Operations.CACHE_CREATION, "createCache");

		Cache cache;
		try {
			cache = new RedisCache(name, redisTemplate, ttl, allowNullValues);

			// 发布缓存创建完成事件
			eventSupport.publishOperationEndEvent(name, name, CacheLayers.CACHE_MANAGER, Operations.CACHE_CREATION, "createCache", 0, true);

			log.info("Successfully created cache '{}' with type: {}", name, cacheType);
			return cache;

		} catch (Exception e) {
			log.error("Failed to create cache '{}': {}", name, e.getMessage());
			eventSupport.publishCacheErrorEvent(name, name, CacheLayers.CACHE_MANAGER, e, Operations.CACHE_CREATION);
			// 回退到基础实现
			return new RedisCache(name, redisTemplate, ttl, allowNullValues);
		}
	}

	/**
	 * 设置缓存操作模板（支持模板方法模式）
	 */
	public void setOperationTemplate(CacheOperationTemplate operationTemplate) {
		this.operationTemplate = operationTemplate;
		log.info("Cache operation template configured: {}", operationTemplate.getClass().getSimpleName());
	}

	@Override
	public void afterPropertiesSet() {
		super.afterPropertiesSet();
	}
}