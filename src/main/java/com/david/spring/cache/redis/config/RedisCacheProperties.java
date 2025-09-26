package com.david.spring.cache.redis.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "spring.redis.cache")
public class RedisCacheProperties {

	private boolean enabled = true;

	private Duration defaultTtl = Duration.ofMinutes(60);

	private boolean allowNullValues = true;

	private String keyPrefix = "";

	private boolean useKeyPrefix = true;

	private Map<String, CacheConfiguration> caches = new HashMap<>();

	private String keyGeneratorBeanName = "redisCacheKeyGenerator";

	private boolean enableTransactions = false;

	private LockConfiguration lock = new LockConfiguration();

	private BloomFilterConfiguration bloomFilter = new BloomFilterConfiguration();

	/**
	 * 通用的缓存配置获取方法
	 */
	private CacheConfiguration getCacheConfiguration(String cacheName) {
		return caches.get(cacheName);
	}

	/**
	 * 获取缓存配置值的通用方法
	 */
	private <T> T getCacheConfigValue(String cacheName, java.util.function.Function<CacheConfiguration, T> configExtractor, T defaultValue) {
		CacheConfiguration config = getCacheConfiguration(cacheName);
		return config != null ? configExtractor.apply(config) : defaultValue;
	}

	/**
	 * 获取缓存配置布尔值的通用方法
	 */
	private boolean getCacheConfigBooleanValue(String cacheName, java.util.function.Function<CacheConfiguration, Boolean> configExtractor) {
		CacheConfiguration config = getCacheConfiguration(cacheName);
		return config != null && configExtractor.apply(config);
	}

	public Duration getCacheTtl(String cacheName) {
		return getCacheConfigValue(cacheName, CacheConfiguration::getTtl, defaultTtl);
	}

	public boolean isCacheAllowNullValues(String cacheName) {
		return getCacheConfigValue(cacheName, CacheConfiguration::isAllowNullValues, allowNullValues);
	}

	public boolean isCacheUseBloomFilter(String cacheName) {
		return getCacheConfigBooleanValue(cacheName, CacheConfiguration::isUseBloomFilter);
	}

	public boolean isCacheEnablePreRefresh(String cacheName) {
		return getCacheConfigBooleanValue(cacheName, CacheConfiguration::isEnablePreRefresh);
	}

	public double getCachePreRefreshThreshold(String cacheName) {
		return getCacheConfigValue(cacheName, CacheConfiguration::getPreRefreshThreshold, 0.3);
	}

	public boolean isCacheRandomTtl(String cacheName) {
		return getCacheConfigBooleanValue(cacheName, CacheConfiguration::isRandomTtl);
	}

	public float getCacheVariance(String cacheName) {
		return getCacheConfigValue(cacheName, CacheConfiguration::getVariance, 0.2f);
	}

	@Data
	public static class CacheConfiguration {
		private Duration ttl;
		private boolean allowNullValues = true;
		private boolean useBloomFilter = false;
		private boolean enablePreRefresh = false;
		private double preRefreshThreshold = 0.3;
		private boolean randomTtl = false;
		private float variance = 0.2f;
	}

	@Data
	public static class LockConfiguration {
		private boolean enabled = false;
		private Duration lockTimeout = Duration.ofSeconds(10);
		private Duration lockWaitTime = Duration.ofSeconds(5);
	}

	@Data
	public static class BloomFilterConfiguration {
		private boolean enabled = false;
		private long expectedInsertions = 1000000L;
		private double falsePositiveProbability = 0.01;
	}
}