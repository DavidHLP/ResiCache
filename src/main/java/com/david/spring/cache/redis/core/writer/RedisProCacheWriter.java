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
		String redisKey = new String(key);
		log.debug("Starting cache retrieval: cacheName={}, key={}, ttl={}", name, redisKey, ttl);
		try {
			CachedValue cachedValue = (CachedValue) redisTemplate.opsForValue().get(redisKey);

			statistics.incGets(name);

			if (cachedValue == null) {
				log.debug("Cache miss - data not found: cacheName={}, key={}", name, redisKey);
				statistics.incMisses(name);
				return null;
			}

			if (cachedValue.isExpired()) {
				log.debug("Cache miss - data expired: cacheName={}, key={}", name, redisKey);
				statistics.incMisses(name);
				return null;
			}

			log.debug("Cache hit: cacheName={}, key={}, remainingTtl={}s", name, redisKey, cachedValue.getRemainingTtl());
			statistics.incHits(name);
			cachedValue.updateAccess();
			redisTemplate.opsForValue().set(redisKey, cachedValue, Duration.ofSeconds(cachedValue.getRemainingTtl()));

			byte[] result = objectMapper.writeValueAsBytes(cachedValue.getValue());
			log.debug("Successfully serialized cache data: cacheName={}, key={}, dataSize={} bytes", name, redisKey, result.length);
			return result;
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
		String redisKey = new String(key);
		log.debug("Starting cache storage: cacheName={}, key={}, ttl={}, dataSize={} bytes", name, redisKey, ttl, value.length);
		try {
			Object deserializedValue = objectMapper.readValue(value, Object.class);

			CachedValue cachedValue;
			if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
				cachedValue = CachedValue.of(deserializedValue, ttl.getSeconds());
				redisTemplate.opsForValue().set(redisKey, cachedValue, ttl);
				log.debug("Successfully stored cache data with TTL: cacheName={}, key={}, ttl={}s", name, redisKey, ttl.getSeconds());
			} else {
				cachedValue = CachedValue.of(deserializedValue, -1);
				redisTemplate.opsForValue().set(redisKey, cachedValue);
				log.debug("Successfully stored permanent cache data: cacheName={}, key={}", name, redisKey);
			}

			statistics.incPuts(name);
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
		String redisKey = new String(key);
		log.debug("Starting conditional cache storage: cacheName={}, key={}, ttl={}, dataSize={} bytes", name, redisKey, ttl, value.length);
		try {
			CachedValue existingValue = (CachedValue) redisTemplate.opsForValue().get(redisKey);

			if (existingValue != null && !existingValue.isExpired()) {
				log.debug("Cache data exists and not expired, returning existing value: cacheName={}, key={}", name, redisKey);
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
				log.debug("Conditional storage succeeded: cacheName={}, key={}", name, redisKey);
				statistics.incPuts(name);
				return null;
			} else {
				log.debug("Conditional storage failed, retrieving existing value: cacheName={}, key={}", name, redisKey);
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
		String redisKey = new String(key);
		log.debug("Starting cache data removal: cacheName={}, key={}", name, redisKey);
		try {
			Boolean deleted = redisTemplate.delete(redisKey);
			statistics.incDeletes(name);
			log.debug("Cache data removal completed: cacheName={}, key={}, deleted={}", name, redisKey, deleted);
		} catch (Exception e) {
			log.error("Failed to remove value from cache: {}", name, e);
		}
	}

	@Override
	public void clean(@NonNull String name, @NonNull byte[] pattern) {
		String keyPattern = new String(pattern);
		log.debug("Starting batch cache cleanup: cacheName={}, pattern={}", name, keyPattern);
		try {
			var keys = redisTemplate.keys(keyPattern);
			log.debug("Found matching cache keys: cacheName={}, pattern={}, count={}", name, keyPattern, keys != null ? keys.size() : 0);
			if (keys != null && !keys.isEmpty()) {
				Long deleteCount = redisTemplate.delete(keys);
				statistics.incDeletesBy(name, deleteCount.intValue());
				log.debug("Batch cache cleanup completed: cacheName={}, pattern={}, deletedCount={}", name, keyPattern, deleteCount);
			} else {
				log.debug("No matching cache keys found: cacheName={}, pattern={}", name, keyPattern);
			}
		} catch (Exception e) {
			log.error("Failed to clean cache: {}", name, e);
		}
	}

	@Override
	public void clearStatistics(@NonNull String name) {
		log.debug("Starting cache statistics cleanup: cacheName={}", name);
		statistics.reset(name);
		log.debug("Cache statistics cleanup completed: cacheName={}", name);
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
