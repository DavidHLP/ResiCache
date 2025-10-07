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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.*;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 实际缓存处理器，负责处理各种缓存操作，包括GET、PUT、PUT_IF_ABSENT、REMOVE和CLEAN操作
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActualCacheHandler extends AbstractCacheHandler {

	private static final int CLEAN_SCAN_COUNT = 512;
	private static final int CLEAN_DELETE_BATCH_SIZE = 256;

	private final RedisTemplate<String, Object> redisTemplate;
	private final ValueOperations<String, Object> valueOperations;
	private final CacheStatisticsCollector statistics;
	private final SyncSupport syncSupport;
	private final TtlPolicy ttlPolicy;
	private final NullValuePolicy nullValuePolicy;
	private final PreRefreshSupport preRefreshSupport;

	/**
	 * 判断是否应该处理给定的缓存上下文
	 *
	 * @param context 缓存上下文
	 * @return 始终返回true，表示总是处理
	 */
	@Override
	protected boolean shouldHandle(CacheContext context) {
		return true;
	}

	/**
	 * 处理缓存操作的主要入口方法
	 *
	 * @param context 缓存上下文，包含操作类型和其他必要信息
	 * @return 缓存操作结果
	 */
	@Override
	protected CacheResult doHandle(CacheContext context) {
		Assert.notNull(context, "CacheContext must not be null");
		Assert.notNull(context.getOperation(), "Cache operation must not be null");

		return dispatchOperation(context);
	}

	/**
	 * 判断给定的缓存上下文是否需要加锁执行
	 *
	 * @param context 缓存上下文
	 * @return 如果需要加锁则返回true，否则返回false
	 */
	private boolean requiresLock(CacheContext context) {
		LockContext lockContext = context.getLockContext();
		return lockContext != null && lockContext.requiresLock();
	}

	/**
	 * 在加锁环境下执行关键缓存操作
	 *
	 * @param context         缓存上下文
	 * @param criticalSection 关键操作代码块
	 * @return 缓存操作结果
	 */
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

	/**
	 * 根据操作类型分发到对应的处理方法
	 *
	 * @param context 缓存上下文
	 * @return 缓存操作结果
	 */
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

	/**
	 * 处理GET操作，从缓存中获取数据
	 *
	 * @param context 缓存上下文
	 * @return 缓存操作结果，包含获取到的数据
	 */
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

	/**
	 * 判断缓存值是否为有效命中
	 *
	 * @param cachedValue 缓存值
	 * @return 如果是有效命中返回true，否则返回false
	 */
	private boolean isCacheHit(CachedValue cachedValue) {
		return cachedValue != null && !cachedValue.isExpired();
	}

	/**
	 * 处理缓存命中情况，包括预刷新逻辑
	 *
	 * @param context     缓存上下文
	 * @param cachedValue 缓存值
	 * @return 缓存操作结果
	 */
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
		long remainingTtl = cachedValue.getRemainingTtl();
		Boolean touched;
		if (remainingTtl >= 0) {
			touched =
					valueOperations.setIfPresent(
							context.getRedisKey(),
							cachedValue,
							Duration.ofSeconds(remainingTtl));
		} else {
			touched = valueOperations.setIfPresent(context.getRedisKey(), cachedValue);
		}

		if (!Boolean.TRUE.equals(touched)) {
			log.debug(
					"Skipped cache touch because key was removed concurrently: cacheName={}, key={}",
					context.getCacheName(),
					context.getRedisKey());
		}

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

	/**
	 * 判断是否需要进行预刷新
	 *
	 * @param context     缓存上下文
	 * @param cachedValue 缓存值
	 * @return 如果需要预刷新返回true，否则返回false
	 */
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

	/**
	 * 处理预刷新逻辑
	 *
	 * @param context     缓存上下文
	 * @param cachedValue 缓存值
	 * @return 缓存操作结果，如果需要同步预刷新则返回miss，否则返回null
	 */
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

			scheduleAsyncPreRefresh(context, cachedValue);
			return null;
		}
	}

	/**
	 * 安排异步预刷新任务
	 *
	 * @param context     缓存上下文
	 * @param cachedValue 缓存值
	 */
	private void scheduleAsyncPreRefresh(CacheContext context, CachedValue cachedValue) {
		String redisKey = context.getRedisKey();
		String cacheName = context.getCacheName();
		LockContext lockContext = context.getLockContext();
		long originalCreated = cachedValue.getCreatedTime();
		long originalVersion = cachedValue.getVersion();

		preRefreshSupport.submitAsyncRefresh(
				redisKey,
				() -> {
					Runnable deleteTask = () -> {
						try {
							CachedValue liveValue = (CachedValue) valueOperations.get(redisKey);
							if (liveValue == null) {
								log.debug(
										"Async pre-refresh found key already missing: cacheName={}, key={}",
										cacheName,
										redisKey);
								return;
							}

							boolean matchesCreated = liveValue.getCreatedTime() == originalCreated;
							boolean matchesVersion = liveValue.getVersion() == originalVersion;

							if (matchesCreated && matchesVersion) {
								Boolean deleted = redisTemplate.delete(redisKey);
								log.debug(
										"Async pre-refresh evicted stale entry: cacheName={}, key={}, deleted={}",
										cacheName,
										redisKey,
										deleted);
							} else {
								log.debug(
										"Async pre-refresh skipped delete because cache value changed: cacheName={}, key={}, originalVersion={}, liveVersion={}, originalCreated={}, liveCreated={}",
										cacheName,
										redisKey,
										originalVersion,
										liveValue.getVersion(),
										originalCreated,
										liveValue.getCreatedTime());
							}
						} catch (Exception ex) {
							log.error(
									"Async pre-refresh failed for cache: cacheName={}, key={}",
									cacheName,
									redisKey,
									ex);
						}
					};

					if (lockContext != null && lockContext.requiresLock()) {
						syncSupport.executeSync(
								lockContext.lockKey(),
								() -> {
									deleteTask.run();
									return Boolean.TRUE;
								},
								lockContext.timeoutSeconds());
					} else {
						deleteTask.run();
					}
				});
	}

	/**
	 * 处理PUT操作，向缓存中存储数据
	 *
	 * @param context 缓存上下文
	 * @return 缓存操作结果
	 */
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
			if (requiresLock(context)) {
				return executeWithLock(context, () -> doPut(context));
			}
			return doPut(context);
		} catch (Exception e) {
			log.error("Failed to put value to cache: {}", context.getCacheName(), e);
			return CacheResult.failure(e);
		}
	}

	/**
	 * 执行实际的PUT操作
	 *
	 * @param context 缓存上下文
	 * @return 缓存操作结果
	 */
	private CacheResult doPut(CacheContext context) {
		preRefreshSupport.cancelAsyncRefresh(context.getRedisKey());

		Object storeValue =
				context.getStoreValue() != null ? context.getStoreValue() : context.getDeserializedValue();

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
	}

	/**
	 * 处理PUT_IF_ABSENT操作，仅在键不存在时存储数据
	 *
	 * @param context 缓存上下文
	 * @return 缓存操作结果
	 */
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

	/**
	 * 处理已存在的缓存值
	 *
	 * @param context       缓存上下文
	 * @param existingValue 已存在的缓存值
	 * @return 如果值存在且未过期则返回成功结果，否则返回null
	 */
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

	/**
	 * 执行条件存储操作
	 *
	 * @param context 缓存上下文
	 * @return 缓存操作结果
	 */
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

	/**
	 * 处理REMOVE操作，从缓存中删除数据
	 *
	 * @param context 缓存上下文
	 * @return 缓存操作结果
	 */
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

	/**
	 * 处理CLEAN操作，批量清理匹配模式的缓存键
	 *
	 * @param context 缓存上下文
	 * @return 缓存操作结果
	 */
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
			LockContext lockContext = context.getLockContext();
			boolean lockRequired = lockContext != null && lockContext.requiresLock();
			AtomicLong totalDeleted = new AtomicLong();

			redisTemplate.execute(
					(RedisCallback<Void>) connection -> {
						ScanOptions scanOptions =
								ScanOptions.scanOptions()
										.match(keyPattern)
										.count(CLEAN_SCAN_COUNT)
										.build();

						List<byte[]> batch = new ArrayList<>(CLEAN_DELETE_BATCH_SIZE);
						try (Cursor<byte[]> cursor = connection.keyCommands().scan(scanOptions)) {
							while (cursor.hasNext()) {
								batch.add(cursor.next());
								if (batch.size() >= CLEAN_DELETE_BATCH_SIZE) {
									long removed =
											deleteBatchWithPolicy(connection, batch, lockRequired, lockContext);
									totalDeleted.addAndGet(removed);
									batch.clear();
								}
							}
							if (!batch.isEmpty()) {
								long removed =
										deleteBatchWithPolicy(connection, batch, lockRequired, lockContext);
								totalDeleted.addAndGet(removed);
								batch.clear();
							}
						} catch (Exception scanException) {
							throw new IllegalStateException(
									String.format(
											"Failed to scan keys for cache cleanup: cacheName=%s, pattern=%s",
											context.getCacheName(),
											keyPattern),
									scanException);
						}
						return null;
					});

			long deletedTotal = totalDeleted.get();
			if (deletedTotal > 0) {
				int reportedDeletes =
						deletedTotal > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) deletedTotal;
				statistics.incDeletesBy(context.getCacheName(), reportedDeletes);
				log.debug(
						"Batch cache cleanup completed: cacheName={}, pattern={}, deletedCount={}",
						context.getCacheName(),
						keyPattern,
						deletedTotal);
			} else {
				log.debug(
						"No matching cache keys found during cleanup: cacheName={}, pattern={}",
						context.getCacheName(),
						keyPattern);
			}

			return CacheResult.success();
		} catch (Exception e) {
			log.error("Failed to clean cache: {}", context.getCacheName(), e);
			return CacheResult.failure(e);
		}
	}

	/**
	 * 根据锁策略删除一批缓存键
	 *
	 * @param connection   Redis连接
	 * @param batch        要删除的键列表
	 * @param lockRequired 是否需要加锁
	 * @param lockContext  锁上下文
	 * @return 删除的键数量
	 */
	private long deleteBatchWithPolicy(
			RedisConnection connection, List<byte[]> batch, boolean lockRequired, LockContext lockContext) {
		if (batch.isEmpty()) {
			return 0L;
		}
		if (lockRequired && lockContext != null) {
			return syncSupport.executeSync(
					lockContext.lockKey(),
					() -> removeBatch(connection, batch),
					lockContext.timeoutSeconds());
		}
		return removeBatch(connection, batch);
	}

	/**
	 * 删除一批缓存键
	 *
	 * @param connection Redis连接
	 * @param batch      要删除的键列表
	 * @return 删除的键数量
	 */
	private long removeBatch(RedisConnection connection, List<byte[]> batch) {
		byte[][] keys = batch.toArray(new byte[batch.size()][]);
		try {
			Long removed = connection.keyCommands().unlink(keys);
			if (removed != null) {
				return removed;
			}
		} catch (Exception ex) {
			log.trace(
					"UNLINK not supported, falling back to DEL for batchSize={}",
					batch.size(),
					ex);
		}
		Long deleted = connection.keyCommands().del(keys);
		return deleted != null ? deleted : 0L;
	}
}
