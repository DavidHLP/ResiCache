package com.david.spring.cache.redis.utils;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.core.RedisProCacheManager;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Slf4j
@Data
@Component
public class CacheUtil {
	private final RedisProCacheManager cacheManager;
	private final Map<String, Long> cacheTtlSeconds = new ConcurrentHashMap<>();

	public CacheUtil(RedisProCacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}

	public void initExpireTime(RedisCacheable redisCacheable) {
		if (redisCacheable == null) {
			log.warn("Skip initializing TTL because redisCacheable is null");
			return;
		}
		String[] names = merge(redisCacheable.value(), redisCacheable.cacheNames());
		long ttl = Math.max(redisCacheable.ttl(), 0);
		log.debug("Initializing TTL with cacheNamesCount={} rawTtlSeconds={}", names.length, ttl);
		for (String name : names) {
			if (name == null || name.isBlank()) continue;
			String trimmed = name.trim();
			Long merged = cacheTtlSeconds.merge(
					trimmed,
					ttl,
					(oldVal, newVal) -> oldVal == 0 ? newVal : newVal == 0 ? oldVal : Math.min(oldVal, newVal));
			log.debug("Registered TTL for cacheName={} ttlSeconds={} (after merge)", trimmed, merged);
		}
	}

	public void initializeCaches() {
		log.info("Initializing cache configurations for {} cache(s)", cacheTtlSeconds.size());
		cacheTtlSeconds.forEach(
				(name, seconds) -> {
					log.debug("Applying TTL to cache configuration: name={} ttlSeconds={}", name, seconds);
					cacheManager
							.getInitialCacheConfigurations()
							.put(
									name,
									// 复用全局 RedisCacheConfiguration，确保序列化配置一致（JSON），仅覆盖 TTL
									cacheManager
											.getRedisCacheConfiguration()
											.entryTtl(Duration.ofSeconds(seconds)));
				});
		cacheManager.initializeCaches();
		log.debug("Completed cache initialization");
	}

	private String[] merge(String[] a, String[] b) {
		return Stream.concat(
						Arrays.stream(Optional.ofNullable(a).orElse(new String[0])),
						Arrays.stream(Optional.ofNullable(b).orElse(new String[0])))
				.toArray(String[]::new);
	}
}
