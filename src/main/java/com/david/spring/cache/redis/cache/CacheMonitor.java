package com.david.spring.cache.redis.cache;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import com.david.spring.cache.redis.registry.EvictInvocationRegistry;
import com.david.spring.cache.redis.strategy.CacheFetchStrategy;
import com.david.spring.cache.redis.strategy.CacheFetchStrategyManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

@Slf4j
public record CacheMonitor(String cacheName, RedisCacheConfiguration cacheConfiguration,
                           RedisTemplate<String, Object> redisTemplate, CacheInvocationRegistry registry,
                           EvictInvocationRegistry evictRegistry, CacheFetchStrategyManager strategyManager,
                           CacheContextValidator validator) {

	public String getStrategyStatus() {
		try {
			return strategyManager.getStrategyInfo();
		} catch (Exception e) {
			return "Strategy status unavailable: " + e.getMessage();
		}
	}

	public String getCacheMonitoringInfo() {
		StringBuilder info = new StringBuilder();
		info.append("=== Cache Monitoring Info for ").append(cacheName).append(" ===\n");

		appendBasicInfo(info);
		appendComponentStatus(info);
		appendStrategyInfo(info);
		appendCacheConfiguration(info);

		return info.toString();
	}

	public String getContextUsageStats() {
		StringBuilder stats = new StringBuilder();
		stats.append("=== Context Usage Statistics ===\n");

		try {
			appendInvocationStats(stats);
			appendStrategyCompatibilityStats(stats);
		} catch (Exception e) {
			stats.append("Statistics collection error: ").append(e.getMessage()).append("\n");
		}

		return stats.toString();
	}

	public boolean isHealthy() {
		try {
			if (!areCoreDependenciesHealthy()) {
				return false;
			}

			if (!isRedisHealthy()) {
				return false;
			}

			return areStrategiesHealthy();

		} catch (Exception e) {
			log.error("Health check failed: {}", e.getMessage());
			return false;
		}
	}

	private void appendBasicInfo(StringBuilder info) {
		info.append("Cache Name: ").append(cacheName).append("\n");
		info.append("Cache Configuration: ").append(cacheConfiguration != null ? "Present" : "Missing").append("\n");
		info.append("Redis Template: ").append(redisTemplate != null ? "Active" : "Inactive").append("\n");
	}

	private void appendComponentStatus(StringBuilder info) {
		info.append("\n--- Component Status ---\n");
		info.append("Registry: ").append(registry != null ? "Active" : "Inactive").append("\n");
		info.append("Evict Registry: ").append(evictRegistry != null ? "Active" : "Inactive").append("\n");
		info.append("Strategy Manager: ").append(strategyManager != null ? "Active" : "Inactive").append("\n");
		info.append("Context Validator: ").append(validator != null ? "Active" : "Inactive").append("\n");
	}

	private void appendStrategyInfo(StringBuilder info) {
		if (strategyManager != null) {
			info.append("\n--- Strategy Information ---\n");
			try {
				info.append(strategyManager.getStrategyInfo());
			} catch (Exception e) {
				info.append("Strategy info error: ").append(e.getMessage()).append("\n");
			}
		}
	}

	private void appendCacheConfiguration(StringBuilder info) {
		if (cacheConfiguration != null) {
			info.append("\n--- Cache Configuration ---\n");
			try {
				info.append("TTL: ").append(cacheConfiguration.getTtl()).append("\n");
				info.append("Key Prefix: ").append(cacheConfiguration.getKeyPrefixFor(cacheName)).append("\n");
				info.append("Cache Null Values: ").append(cacheConfiguration.getAllowCacheNullValues()).append("\n");
				info.append("Use Key Prefix: ").append(cacheConfiguration.usePrefix()).append("\n");
			} catch (Exception e) {
				info.append("Configuration details error: ").append(e.getMessage()).append("\n");
			}
		}
	}

	private void appendInvocationStats(StringBuilder stats) {
		if (registry != null) {
			stats.append("Active Invocations: Available\n");
		} else {
			stats.append("Active Invocations: Registry unavailable\n");
		}
	}

	private void appendStrategyCompatibilityStats(StringBuilder stats) {
		if (strategyManager != null) {
			List<CacheFetchStrategy> strategies = strategyManager.getAllStrategies();
			int compatibleStrategies = 0;

			for (CacheFetchStrategy strategy : strategies) {
				try {
					CachedInvocationContext testContext = validator.createDefaultContext(cacheName);
					if (strategy.isStrategyTypeCompatible(testContext.fetchStrategy())) {
						compatibleStrategies++;
					}
				} catch (Exception e) {
					// 忽略检查错误
				}
			}

			stats.append("Total Strategies: ").append(strategies.size()).append("\n");
			stats.append("Compatible Strategies: ").append(compatibleStrategies).append("\n");
		}
	}

	private boolean areCoreDependenciesHealthy() {
		return strategyManager != null && validator != null;
	}

	private boolean isRedisHealthy() {
		if (redisTemplate == null) {
			return false;
		}

		try {
			redisTemplate.hasKey("health-check-key");
			return true;
		} catch (Exception e) {
			log.warn("Redis connectivity check failed: {}", e.getMessage());
			return false;
		}
	}

	private boolean areStrategiesHealthy() {
		List<CacheFetchStrategy> strategies = strategyManager.getAllStrategies();
		return !strategies.isEmpty();
	}
}