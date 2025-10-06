package com.david.spring.cache.redis.core.writer.chain.handler;

import com.david.spring.cache.redis.core.writer.CachedValue;
import com.david.spring.cache.redis.core.writer.chain.CacheOperation;
import com.david.spring.cache.redis.core.writer.chain.CacheResult;
import com.david.spring.cache.redis.core.writer.support.lock.SyncSupport;
import com.david.spring.cache.redis.core.writer.support.protect.nullvalue.NullValuePolicy;
import com.david.spring.cache.redis.core.writer.support.protect.ttl.TtlPolicy;
import com.david.spring.cache.redis.core.writer.support.refresh.PreRefreshMode;
import com.david.spring.cache.redis.core.writer.support.refresh.PreRefreshSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

/**  */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActualCacheHandler extends AbstractCacheHandler {

	private final RedisTemplate<String, Object> redisTemplate;
	private final ValueOperations<String, Object> valueOperations;
	private final CacheStatisticsCollector statistics;
	private final SyncSupport syncSupport;
	private final TtlPolicy ttlPolicy;
	private final NullValuePolicy nullValuePolicy;
	private final PreRefreshSupport preRefreshSupport;

	@Override
	protected boolean shouldHandle(CacheContext context) {
		return true;
	}

	@Override
	protected CacheResult doHandle(CacheContext context) {
		Assert.notNull(context, "CacheContext must not be null");
		Assert.notNull(context.getOperation(), "Cache operation must not be null");

		return dispatchOperation(context);
	}

	private boolean requiresLock(CacheContext context) {
		LockContext lockContext = context.getLockContext();
		return lockContext != null && lockContext.requiresLock();
	}

	private CacheResult executeWithLock(CacheContext context, Supplier<CacheResult> criticalSection) {
		LockContext lockContext = context.getLockContext();
		Assert.notNull(lockContext, "LockContext must not be null when lock is required");
		Assert.hasText(lockContext.lockKey(), "Lock key must not be empty when lock is required");

		log.debug(
				"Executing critical cache section with sync lock: cacheName={}, key={}, operation={}, timeout={}s",
				context.getCacheName(),
				lockContext.lockKey(),
				context.getOperation(),
				lockContext.timeoutSeconds());

		return syncSupport.executeSync(
				lockContext.lockKey(),
				criticalSection,
				lockContext.timeoutSeconds());
	}

	private CacheResult dispatchOperation(CacheContext context) {
		CacheOperation operation = context.getOperation();

		return switch (operation) {
			case GET -> handleGet(context);
			case PUT -> handlePut(context);
			case PUT_IF_ABSENT -> handlePutIfAbsent(context);
			case REMOVE -> handleRemove(context);
			case CLEAN -> handleClean(context);
		};
	}

	private CacheResult handleGet(CacheContext context) {
		Assert.notNull(context, "CacheContext must not be null");
		Assert.hasText(context.getCacheName(), "Cache name must not be empty");
		Assert.hasText(context.getRedisKey(), "Redis key must not be empty");

		log.debug(
				"Starting cache retrieval: cacheName={}, key={}, ttl={}",
				context.getCacheName(),
				context.getRedisKey(),
				context.getTtl());

		try {
			statistics.incGets(context.getCacheName());

			CachedValue cachedValue = (CachedValue) valueOperations.get(context.getRedisKey());

			if (isCacheHit(cachedValue)) {
				return processCacheHit(context, cachedValue);
			}

			log.debug(
					"Cache miss detected before lock: cacheName={}, key={}",
					context.getCacheName(),
					context.getRedisKey());

			if (!requiresLock(context)) {
				statistics.incMisses(context.getCacheName());
				return CacheResult.miss();
			}

			return executeWithLock(
					context,
					() -> {
						CachedValue lockedValue =
								(CachedValue) valueOperations.get(context.getRedisKey());

						if (isCacheHit(lockedValue)) {
							return processCacheHit(context, lockedValue);
						}

						log.debug(
								"Cache miss confirmed after lock: cacheName={}, key={}",
								context.getCacheName(),
								context.getRedisKey());
						statistics.incMisses(context.getCacheName());
						return CacheResult.miss();
					});
		} catch (Exception e) {
			log.error("Failed to get value from cache: {}", context.getCacheName(), e);
			statistics.incMisses(context.getCacheName());
			return CacheResult.failure(e);
		}
	}

	private boolean isCacheHit(CachedValue cachedValue) {
		return cachedValue != null && !cachedValue.isExpired();
	}

	private CacheResult processCacheHit(CacheContext context, CachedValue cachedValue) {
		if (shouldPreRefresh(context, cachedValue)) {
			CacheResult preRefreshResult = handlePreRefresh(context, cachedValue);
			if (preRefreshResult != null) {
				return preRefreshResult;
			}
		}

		log.debug(
				"Cache hit: cacheName={}, key={}, remainingTtl={}s",
				context.getCacheName(),
				context.getRedisKey(),
				cachedValue.getRemainingTtl());

		statistics.incHits(context.getCacheName());

		cachedValue.updateAccess();
		valueOperations.set(
				context.getRedisKey(),
				cachedValue,
				Duration.ofSeconds(cachedValue.getRemainingTtl()));

		byte[] result =
				nullValuePolicy.toReturnValue(
						cachedValue.getValue(), context.getCacheName(), context.getRedisKey());

		if (result != null && !nullValuePolicy.isNullValue(cachedValue.getValue())) {
			log.debug(
					"Successfully serialized cache data: cacheName={}, key={}, dataSize={} bytes",
					context.getCacheName(),
					context.getRedisKey(),
					result.length);
		}

		return CacheResult.success(result);
	}

	private boolean shouldPreRefresh(CacheContext context, CachedValue cachedValue) {
		Assert.notNull(context, "CacheContext must not be null");
		Assert.notNull(cachedValue, "CachedValue must not be null");

		return context.getCacheOperation() != null
				&& context.getCacheOperation().isEnablePreRefresh()
				&& ttlPolicy.shouldPreRefresh(
				cachedValue.getCreatedTime(),
				cachedValue.getTtl(),
				context.getCacheOperation().getPreRefreshThreshold());
	}

	private CacheResult handlePreRefresh(CacheContext context, CachedValue cachedValue) {
		Assert.notNull(context, "CacheContext must not be null");
		Assert.notNull(cachedValue, "CachedValue must not be null");
		Assert.hasText(context.getRedisKey(), "Redis key must not be empty");

		if (context.getCacheOperation() != null) {
			log.info(
					"Cache needs pre-refresh: cacheName={}, key={}, threshold={}, remainingTtl={}s",
					context.getCacheName(),
					context.getRedisKey(),
					context.getCacheOperation().getPreRefreshThreshold(),
					cachedValue.getRemainingTtl());
		}
		PreRefreshMode mode = null;
		if (context.getCacheOperation() != null) {
			mode = context.getCacheOperation().getPreRefreshMode();
		}
		if (mode == null) {
			mode = PreRefreshMode.SYNC;
		}

		if (mode == PreRefreshMode.SYNC) {
			log.info(
					"Synchronous pre-refresh triggered, returning null to trigger cache miss: cacheName={}, key={}",
					context.getCacheName(),
					context.getRedisKey());
			statistics.incMisses(context.getCacheName());
			return CacheResult.miss();
		} else {
			log.info(
					"Asynchronous pre-refresh triggered, returning old value and refreshing cache in background: cacheName={}, key={}",
					context.getCacheName(),
					context.getRedisKey());

			preRefreshSupport.submitAsyncRefresh(
					context.getRedisKey(),
					() -> {
						try {
							redisTemplate.delete(context.getRedisKey());
							log.debug(
									"Asynchronous pre-refresh successfully deleted cache: cacheName={}, key={}",
									context.getCacheName(),
									context.getRedisKey());
						} catch (Exception e) {
							log.error(
									"Asynchronous pre-refresh failed: cacheName={}, key={}",
									context.getCacheName(),
									context.getRedisKey(),
									e);
						}
					});
			return null;
		}
	}

	/** 澶勭悊 PUT 鎿嶄綔 */
	private CacheResult handlePut(CacheContext context) {
		Assert.notNull(context, "CacheContext must not be null");
		Assert.hasText(context.getCacheName(), "Cache name must not be empty");
		Assert.hasText(context.getRedisKey(), "Redis key must not be empty");

		log.debug(
				"Starting cache storage: cacheName={}, key={}, ttl={}, dataSize={} bytes",
				context.getCacheName(),
				context.getRedisKey(),
				context.getTtl(),
				context.getValueBytes() != null ? context.getValueBytes().length : 0);

		try {

			Object storeValue =
					context.getStoreValue() != null
							? context.getStoreValue()
							: context.getDeserializedValue();

			CachedValue cachedValue;
			if (context.isShouldApplyTtl()) {
				cachedValue = CachedValue.of(storeValue, context.getFinalTtl());
				valueOperations.set(
						context.getRedisKey(),
						cachedValue,
						Duration.ofSeconds(context.getFinalTtl()));

				log.debug(
						"Successfully stored cache data with TTL: cacheName={}, key={}, ttl={}s, fromContext={}, isNull={}",
						context.getCacheName(),
						context.getRedisKey(),
						context.getFinalTtl(),
						context.isTtlFromContext(),
						context.getDeserializedValue() == null);
			} else {
				cachedValue = CachedValue.of(storeValue, -1);
				valueOperations.set(context.getRedisKey(), cachedValue);

				log.debug(
						"Successfully stored permanent cache data: cacheName={}, key={}, isNull={}",
						context.getCacheName(),
						context.getRedisKey(),
						context.getDeserializedValue() == null);
			}

			statistics.incPuts(context.getCacheName());
			return CacheResult.success();

		} catch (Exception e) {
			log.error("Failed to put value to cache: {}", context.getCacheName(), e);
			return CacheResult.failure(e);
		}
	}

	/** 澶勭悊 PUT_IF_ABSENT 鎿嶄綔 */
	private CacheResult handlePutIfAbsent(CacheContext context) {
		Assert.notNull(context, "CacheContext must not be null");
		Assert.hasText(context.getCacheName(), "Cache name must not be empty");
		Assert.hasText(context.getRedisKey(), "Redis key must not be empty");

		log.debug(
				"Starting conditional cache storage: cacheName={}, key={}, ttl={}, dataSize={} bytes",
				context.getCacheName(),
				context.getRedisKey(),
				context.getTtl(),
				context.getValueBytes() != null ? context.getValueBytes().length : 0);

		try {
			CachedValue existingValue = (CachedValue) valueOperations.get(context.getRedisKey());
			CacheResult existingResult = handleExistingValue(context, existingValue);
			if (existingResult != null) {
				return existingResult;
			}

			if (requiresLock(context)) {
				return executeWithLock(context, () -> performConditionalStore(context));
			}

			return performConditionalStore(context);

		} catch (Exception e) {
			log.error("Failed to putIfAbsent value to cache: {}", context.getCacheName(), e);
			return CacheResult.failure(e);
		}
	}

	private CacheResult handleExistingValue(CacheContext context, CachedValue existingValue) {
		if (!isCacheHit(existingValue)) {
			return null;
		}

		log.debug(
				"Cache data exists and not expired, returning existing value: cacheName={}, key={}",
				context.getCacheName(),
				context.getRedisKey());

		byte[] result =
				nullValuePolicy.toReturnValue(
						existingValue.getValue(),
						context.getCacheName(),
						context.getRedisKey());
		return CacheResult.success(result);
	}

	private CacheResult performConditionalStore(CacheContext context) {
		CachedValue recheckedValue = (CachedValue) valueOperations.get(context.getRedisKey());
		CacheResult existingResult = handleExistingValue(context, recheckedValue);
		if (existingResult != null) {
			return existingResult;
		}

		Object storeValue =
				context.getStoreValue() != null
						? context.getStoreValue()
						: context.getDeserializedValue();

		CachedValue cachedValue;
		Boolean success;

		if (context.isShouldApplyTtl()) {
			cachedValue = CachedValue.of(storeValue, context.getFinalTtl());
			success =
					valueOperations.setIfAbsent(
							context.getRedisKey(),
							cachedValue,
							Duration.ofSeconds(context.getFinalTtl()));

			log.debug(
					"Attempting conditional storage with TTL: cacheName={}, key={}, ttl={}s, fromContext={}, isNull={}",
					context.getCacheName(),
					context.getRedisKey(),
					context.getFinalTtl(),
					context.isTtlFromContext(),
					context.getDeserializedValue() == null);
		} else {
			cachedValue = CachedValue.of(storeValue, -1);
			success = valueOperations.setIfAbsent(context.getRedisKey(), cachedValue);

			log.debug(
					"Attempting conditional storage without TTL: cacheName={}, key={}, isNull={}",
					context.getCacheName(),
					context.getRedisKey(),
					context.getDeserializedValue() == null);
		}

		if (Boolean.TRUE.equals(success)) {
			log.debug(
					"Conditional storage succeeded: cacheName={}, key={}",
					context.getCacheName(),
					context.getRedisKey());
			statistics.incPuts(context.getCacheName());
			return CacheResult.success();
		}

		log.debug(
				"Conditional storage failed, retrieving existing value: cacheName={}, key={}",
				context.getCacheName(),
				context.getRedisKey());

		CachedValue actualValue = (CachedValue) valueOperations.get(context.getRedisKey());
		if (actualValue != null) {
			byte[] result =
					nullValuePolicy.toReturnValue(
							actualValue.getValue(),
							context.getCacheName(),
							context.getRedisKey());
			return CacheResult.success(result);
		}

		return CacheResult.success();
	}

	/** 澶勭悊 REMOVE 鎿嶄綔 */
	private CacheResult handleRemove(CacheContext context) {
		Assert.notNull(context, "CacheContext must not be null");
		Assert.hasText(context.getCacheName(), "Cache name must not be empty");
		Assert.hasText(context.getRedisKey(), "Redis key must not be empty");

		log.debug(
				"Starting cache data removal: cacheName={}, key={}",
				context.getCacheName(),
				context.getRedisKey());

		try {
			Boolean deleted = redisTemplate.delete(context.getRedisKey());
			statistics.incDeletes(context.getCacheName());

			log.debug(
					"Cache data removal completed: cacheName={}, key={}, deleted={}",
					context.getCacheName(),
					context.getRedisKey(),
					deleted);

			return CacheResult.success();
		} catch (Exception e) {
			log.error("Failed to remove value from cache: {}", context.getCacheName(), e);
			return CacheResult.failure(e);
		}
	}

	/** 澶勭悊 CLEAN 鎿嶄綔 */
	private CacheResult handleClean(CacheContext context) {
		Assert.notNull(context, "CacheContext must not be null");
		Assert.hasText(context.getCacheName(), "Cache name must not be empty");

		String keyPattern = context.getKeyPattern();
		Assert.hasText(keyPattern, "Key pattern must not be empty");
		log.debug(
				"Starting batch cache cleanup: cacheName={}, pattern={}",
				context.getCacheName(),
				keyPattern);

		try {
			Set<String> keys = redisTemplate.keys(keyPattern);
			log.debug(
					"Found matching cache keys: cacheName={}, pattern={}, count={}",
					context.getCacheName(),
					keyPattern,
					keys.size());

			if (!keys.isEmpty()) {
				Long deleteCount = redisTemplate.delete(keys);
				statistics.incDeletesBy(context.getCacheName(), deleteCount.intValue());

				log.debug(
						"Batch cache cleanup completed: cacheName={}, pattern={}, deletedCount={}",
						context.getCacheName(),
						keyPattern,
						deleteCount);
			} else {
				log.debug(
						"No matching cache keys found: cacheName={}, pattern={}",
						context.getCacheName(),
						keyPattern);
			}

			return CacheResult.success();
		} catch (Exception e) {
			log.error("Failed to clean cache: {}", context.getCacheName(), e);
			return CacheResult.failure(e);
		}
	}
}
