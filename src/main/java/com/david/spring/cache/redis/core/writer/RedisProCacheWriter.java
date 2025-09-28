package com.david.spring.cache.redis.core.writer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RequiredArgsConstructor
public class RedisProCacheWriter implements RedisCacheWriter {
	private final RedisTemplate<String, Object> redisTemplate;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final CacheStatisticsCollector statistics;

	@Override
	@Nullable
	public byte[] get(@NonNull String name, @NonNull byte[] key) {
		return get(name, key, null);
	}

	@Override
	@Nullable
	public byte[] get(@NonNull String name, @NonNull byte[] key, @Nullable Duration ttl) {
		try {
			String redisKey = new String(key);
			CachedValue cachedValue = (CachedValue) redisTemplate.opsForValue().get(redisKey);

			statistics.incGets(name);

			if (cachedValue == null || cachedValue.isExpired()) {
				statistics.incMisses(name);
				return null;
			}

			statistics.incHits(name);
			cachedValue.updateAccess();
			redisTemplate.opsForValue().set(redisKey, cachedValue, Duration.ofSeconds(cachedValue.getRemainingTtl()));

			return objectMapper.writeValueAsBytes(cachedValue.getValue());
		} catch (JsonProcessingException e) {
			log.error("Failed to serialize cached value", e);
			statistics.incMisses(name);
			return null;
		} catch (Exception e) {
			log.error("Failed to get value from cache: {}", name, e);
			statistics.incMisses(name);
			return null;
		}
	}

	@Override
	public boolean supportsAsyncRetrieve() {
		return RedisCacheWriter.super.supportsAsyncRetrieve();
	}

	@Override
	@NonNull
	public CompletableFuture<byte[]> retrieve(@NonNull String name, @NonNull byte[] key) {
		return retrieve(name, key, null);
	}

	@Override
	@NonNull
	public CompletableFuture<byte[]> retrieve(@NonNull String name, @NonNull byte[] key, @Nullable Duration ttl) {
		return CompletableFuture.supplyAsync(() -> get(name, key, ttl));
	}

	@Override
	public void put(@NonNull String name, @NonNull byte[] key, @NonNull byte[] value, @Nullable Duration ttl) {
		try {
			String redisKey = new String(key);
			Object deserializedValue = objectMapper.readValue(value, Object.class);

			CachedValue cachedValue;
			if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
				cachedValue = CachedValue.of(deserializedValue, ttl.getSeconds());
				redisTemplate.opsForValue().set(redisKey, cachedValue, ttl);
			} else {
				cachedValue = CachedValue.of(deserializedValue, -1);
				redisTemplate.opsForValue().set(redisKey, cachedValue);
			}

			statistics.incPuts(name);
			log.debug("Cached value for key: {} with TTL: {}", redisKey, ttl);
		} catch (JsonProcessingException e) {
			log.error("Failed to deserialize value for caching", e);
		} catch (Exception e) {
			log.error("Failed to put value to cache: {}", name, e);
		}
	}

	@Override
	@NonNull
	public CompletableFuture<Void> store(@NonNull String name, @NonNull byte[] key, @NonNull byte[] value, @Nullable Duration ttl) {
		return CompletableFuture.runAsync(() -> put(name, key, value, ttl));
	}

	@Override
	@Nullable
	public byte[] putIfAbsent(@NonNull String name, @NonNull byte[] key, @NonNull byte[] value, @Nullable Duration ttl) {
		try {
			String redisKey = new String(key);
			CachedValue existingValue = (CachedValue) redisTemplate.opsForValue().get(redisKey);

			if (existingValue != null && !existingValue.isExpired()) {
				return objectMapper.writeValueAsBytes(existingValue.getValue());
			}

			Object deserializedValue = objectMapper.readValue(value, Object.class);
			CachedValue cachedValue;
			Boolean success;

			if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
				cachedValue = CachedValue.of(deserializedValue, ttl.getSeconds());
				success = redisTemplate.opsForValue().setIfAbsent(redisKey, cachedValue, ttl);
			} else {
				cachedValue = CachedValue.of(deserializedValue, -1);
				success = redisTemplate.opsForValue().setIfAbsent(redisKey, cachedValue);
			}

			if (Boolean.TRUE.equals(success)) {
				statistics.incPuts(name);
				return null;
			} else {
				CachedValue actualValue = (CachedValue) redisTemplate.opsForValue().get(redisKey);
				return actualValue != null ? objectMapper.writeValueAsBytes(actualValue.getValue()) : null;
			}
		} catch (JsonProcessingException e) {
			log.error("Failed to process value for putIfAbsent", e);
			return null;
		} catch (Exception e) {
			log.error("Failed to putIfAbsent value to cache: {}", name, e);
			return null;
		}
	}

	@Override
	public void remove(@NonNull String name, @NonNull byte[] key) {
		try {
			String redisKey = new String(key);
			redisTemplate.delete(redisKey);
			statistics.incDeletes(name);
			log.debug("Removed cache entry for key: {}", redisKey);
		} catch (Exception e) {
			log.error("Failed to remove value from cache: {}", name, e);
		}
	}

	@Override
	public void clean(@NonNull String name, @NonNull byte[] pattern) {
		try {
			String keyPattern = new String(pattern);
			var keys = redisTemplate.keys(keyPattern);
			if (!keys.isEmpty()) {
				Long deleteCount = redisTemplate.delete(keys);
				statistics.incDeletesBy(name, deleteCount.intValue());
			}
			log.debug("Cleaned cache entries matching pattern: {}", keyPattern);
		} catch (Exception e) {
			log.error("Failed to clean cache: {}", name, e);
		}
	}

	@Override
	public void clearStatistics(@NonNull String name) {
		statistics.reset(name);
		log.debug("Clear statistics for cache: {}", name);
	}

	@Override
	@NonNull
	public RedisCacheWriter withStatisticsCollector(@NonNull CacheStatisticsCollector cacheStatisticsCollector) {
		return new RedisProCacheWriter(redisTemplate, cacheStatisticsCollector);
	}

	@Override
	@NonNull
	public CacheStatistics getCacheStatistics(@NonNull String cacheName) {
		return statistics.getCacheStatistics(cacheName);
	}
}
