package com.david.spring.cache.redis.core;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class RedisCacheLogUtils {
	public static void logCacheStatisticsUpdateFailure(String cacheKey, Exception e) {
		log.warn("Failed to update cache statistics for key '{}': {}", cacheKey, e.getMessage());
	}
}
