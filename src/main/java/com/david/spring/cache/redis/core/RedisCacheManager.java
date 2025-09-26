package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.event.entity.CacheErrorEvent;
import com.david.spring.cache.redis.event.entity.CacheOperationEndEvent;
import com.david.spring.cache.redis.event.entity.CacheOperationStartEvent;
import com.david.spring.cache.redis.event.publisher.CacheEventPublisher;
import com.david.spring.cache.redis.template.CacheOperationTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.cache.CacheType;
import org.springframework.cache.Cache;
import org.springframework.cache.transaction.AbstractTransactionSupportingCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.david.spring.cache.redis.core.CacheConstants.*;

@Slf4j
public class RedisCacheManager extends AbstractTransactionSupportingCacheManager {

	private final RedisTemplate<String, Object> redisTemplate;
	private final Map<String, Duration> cacheConfigurations;
	private final Duration defaultTtl;
	private final boolean allowNullValues;
	private final Map<String, Cache> cacheMap = new ConcurrentHashMap<>();
	private final Map<String, CacheType> cacheTypes = new ConcurrentHashMap<>();
	private final Map<String, CacheManagerMetrics> cacheMetrics = new ConcurrentHashMap<>();

	// 缓存操作模板支持
	private CacheOperationTemplate operationTemplate;
	// 事件发布器支持
	private CacheEventPublisher eventPublisher;
	// 事件支持（通过抽象类）
	private final AbstractEventAwareCache eventSupport = new AbstractEventAwareCache() {};
	// 应用事件发布器
	private ApplicationEventPublisher applicationEventPublisher;
	// Bean 工厂
	private BeanFactory beanFactory;
	// 默认缓存类型
	private CacheType defaultCacheType = CacheType.REDIS;
	// 智能管理开关
	private boolean intelligentManagementEnabled = false;

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
		CacheType cacheType = cacheTypes.getOrDefault(name, defaultCacheType);

		log.debug("Creating cache '{}' with type: {}, TTL: {}", name, cacheType, ttl);

		// 发布缓存创建开始事件
		eventSupport.publishOperationStartEvent(name, name, CacheLayers.CACHE_MANAGER, Operations.CACHE_CREATION, "createCache");

		Cache cache;
		try {
			cache = switch (cacheType) {
				case REDIS -> createEnhancedRedisCache(name, ttl);
				case CAFFEINE ->
					// 如果需要本地缓存，可以在这里扩展
						createEnhancedRedisCache(name, ttl);
				default -> createEnhancedRedisCache(name, ttl);
			};

			// 初始化缓存指标
			cacheMetrics.put(name, new CacheManagerMetrics());

			// 发布缓存创建完成事件
			eventSupport.publishOperationEndEvent(name, name, CacheLayers.CACHE_MANAGER, Operations.CACHE_CREATION, "createCache", 0, true);

			log.info("Successfully created cache '{}' with type: {}", name, cacheType);
			return cache;

		} catch (Exception e) {
			log.error("Failed to create cache '{}': {}", name, e.getMessage());
			eventSupport.publishCacheErrorEvent(name, name, CacheLayers.CACHE_MANAGER, e, Operations.CACHE_CREATION);
			// 回退到基础实现
			return createBasicRedisCache(name, ttl);
		}
	}

	private Cache createEnhancedRedisCache(String name, Duration ttl) {
		RedisCache redisCache = new RedisCache(name, redisTemplate, ttl, allowNullValues);

		// 注入依赖
		if (operationTemplate != null) {
			redisCache.setOperationTemplate(operationTemplate);
		}
		if (eventPublisher != null) {
			redisCache.setEventPublisher(eventPublisher);
		}

		// 如果启用智能管理，添加增强功能
		if (intelligentManagementEnabled) {
			enhanceCacheWithIntelligentFeatures(redisCache);
		}

		return redisCache;
	}

	private Cache createBasicRedisCache(String name, Duration ttl) {
		RedisCache redisCache = new RedisCache(name, redisTemplate, ttl, allowNullValues);
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
		updateExistingCaches();
	}

	/**
	 * 设置事件发布器
	 */
	public void setEventPublisher(CacheEventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
		eventSupport.setEventPublisher(eventPublisher);
		log.info("Cache event publisher configured: {}", eventPublisher.getClass().getSimpleName());
		// 更新已创建的缓存
		updateExistingCaches();
	}

	/**
	 * 设置应用事件发布器
	 */
	public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
		this.applicationEventPublisher = applicationEventPublisher;
	}

	/**
	 * 设置 Bean 工厂
	 */
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * 启用智能管理
	 */
	public void enableIntelligentManagement() {
		this.intelligentManagementEnabled = true;
		log.info("Intelligent cache management enabled");
		// 重新创建所有缓存以应用智能功能
		recreateAllCaches();
	}

	/**
	 * 禁用智能管理
	 */
	public void disableIntelligentManagement() {
		this.intelligentManagementEnabled = false;
		log.info("Intelligent cache management disabled");
	}

	/**
	 * 更新已存在的缓存配置
	 */
	private void updateExistingCaches() {
		cacheMap.values().forEach(cache -> {
			if (cache instanceof RedisCache redisCache) {
				if (operationTemplate != null) {
					redisCache.setOperationTemplate(operationTemplate);
				}
				if (eventPublisher != null) {
					redisCache.setEventPublisher(eventPublisher);
				}
			}
		});
	}

	/**
	 * 重新创建所有缓存
	 */
	private void recreateAllCaches() {
		Set<String> cacheNames = new HashSet<>(cacheMap.keySet());
		cacheMap.clear();
		log.info("Recreating {} caches with new configuration", cacheNames.size());

		// 预热关键缓存
		cacheNames.forEach(this::getCache);
	}

	@Override
	public void afterPropertiesSet() {
		super.afterPropertiesSet();
		log.info("RedisCacheManager initialized with default TTL: {}, allowNullValues: {}, intelligentManagement: {}",
				defaultTtl, allowNullValues, intelligentManagementEnabled);
	}

	/**
	 * 智能增强缓存功能
	 */
	private void enhanceCacheWithIntelligentFeatures(RedisCache cache) {
		// 这里可以添加智能功能，如：
		// 1. 自动预刷新
		// 2. 智能TTL调整
		// 3. 异常检测和恢复
		// 4. 性能监控和优化建议
		log.debug("Enhanced cache '{}' with intelligent features", cache.getName());
	}


	/**
	 * 获取缓存管理器指标
	 */
	public CacheManagerMetrics getCacheManagerMetrics(String cacheName) {
		return cacheMetrics.get(cacheName);
	}

	/**
	 * 获取所有缓存指标
	 */
	public Map<String, CacheManagerMetrics> getAllCacheMetrics() {
		return new ConcurrentHashMap<>(cacheMetrics);
	}

	/**
	 * 获取缓存健康状态
	 */
	public boolean isCacheHealthy(String cacheName) {
		CacheManagerMetrics metrics = cacheMetrics.get(cacheName);
		return metrics == null || metrics.isHealthy();
	}

	/**
	 * 执行缓存健康检查
	 */
	public void performHealthCheck() {
		log.debug("Performing cache health check for {} caches", cacheMap.size());

		for (Map.Entry<String, Cache> entry : cacheMap.entrySet()) {
			String cacheName = entry.getKey();
			Cache cache = entry.getValue();

			try {
				// 简单的健康检查 - 尝试放入和获取一个测试值
				Object testKey = HEALTH_CHECK_KEY_PREFIX + System.currentTimeMillis();
				cache.put(testKey, "test");
				cache.get(testKey);
				cache.evict(testKey);

				// 更新健康状态
				CacheManagerMetrics metrics = cacheMetrics.get(cacheName);
				if (metrics != null) {
					metrics.recordHealthCheck(true);
				}

				log.debug("Cache '{}' health check passed", cacheName);

			} catch (Exception e) {
				log.warn("Cache '{}' health check failed: {}", cacheName, e.getMessage());

				// 更新健康状态
				CacheManagerMetrics metrics = cacheMetrics.get(cacheName);
				if (metrics != null) {
					metrics.recordHealthCheck(false);
				}

				// 发布健康检查失败事件
				eventSupport.publishCacheErrorEvent(cacheName, cacheName, CacheLayers.CACHE_MANAGER, e, Operations.HEALTH_CHECK);
			}
		}
	}

	/**
	 * 缓存管理器指标
	 */
	public static class CacheManagerMetrics {
		private final long creationTime = System.currentTimeMillis();
		private volatile boolean healthy = true;
		private volatile long lastHealthCheck = System.currentTimeMillis();
		private volatile int consecutiveFailures = 0;

		public void recordHealthCheck(boolean successful) {
			this.lastHealthCheck = System.currentTimeMillis();
			if (successful) {
				this.consecutiveFailures = 0;
				this.healthy = true;
			} else {
				this.consecutiveFailures++;
				// 连续3次失败认为不健康
				this.healthy = consecutiveFailures < 3;
			}
		}

		public boolean isHealthy() {
			return healthy;
		}

		public long getLastHealthCheck() {
			return lastHealthCheck;
		}

		public int getConsecutiveFailures() {
			return consecutiveFailures;
		}

		public long getAge() {
			return System.currentTimeMillis() - creationTime;
		}
	}
}