package com.david.spring.cache.redis.cache;

import com.david.spring.cache.redis.locks.DistributedLock;
import com.david.spring.cache.redis.locks.LockUtils;
import com.david.spring.cache.redis.meta.CacheMata;
import com.david.spring.cache.redis.protection.CacheAvalanche;
import com.david.spring.cache.redis.protection.CacheBreakdown;
import com.david.spring.cache.redis.protection.CachePenetration;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import com.david.spring.cache.redis.registry.EvictInvocationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class RedisProCache extends RedisCache {

	private static final long DOUBLE_DELETE_DELAY_MS = 300L;
	private final RedisTemplate<String, Object> redisTemplate;
	private final CacheInvocationRegistry registry;
	private final EvictInvocationRegistry evictRegistry;
	private final Executor executor;
	private final RedisCacheConfiguration cacheConfiguration;
	private final DistributedLock distributedLock;
	private final CachePenetration cachePenetration;
	private final CacheBreakdown cacheBreakdown;
	private final CacheAvalanche cacheAvalanche;

	public RedisProCache(
			String name,
			RedisCacheWriter cacheWriter,
			RedisCacheConfiguration cacheConfiguration,
			RedisTemplate<String, Object> redisTemplate,
			CacheInvocationRegistry registry,
			EvictInvocationRegistry evictRegistry,
			Executor executor,
			DistributedLock distributedLock,
			CachePenetration cachePenetration,
			CacheBreakdown cacheBreakdown,
			CacheAvalanche cacheAvalanche) {
		super(name, cacheWriter, cacheConfiguration);
		this.redisTemplate = Objects.requireNonNull(redisTemplate);
		this.registry = Objects.requireNonNull(registry);
		this.evictRegistry = Objects.requireNonNull(evictRegistry);
		this.executor = Objects.requireNonNull(executor);
		this.cacheConfiguration = Objects.requireNonNull(cacheConfiguration);
		this.distributedLock = Objects.requireNonNull(distributedLock);
		this.cachePenetration = Objects.requireNonNull(cachePenetration);
		this.cacheBreakdown = Objects.requireNonNull(cacheBreakdown);
		this.cacheAvalanche = Objects.requireNonNull(cacheAvalanche);
		log.info("RedisProCache initialized, name={}", name);
	}

	@Override
	@Nullable
	public ValueWrapper get(@NonNull Object key) {
		ValueWrapper valueWrapper = super.get(key);
		if (valueWrapper == null) {
			log.debug("Cache miss for key: {}", key);
			return null;
		}

		log.debug("Cache hit for key: {}", key);

		try {
			String cacheKey = createCacheKey(key);
			long ttl = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
			long configuredTtl = resolveConfiguredTtlSeconds(valueWrapper.get(), key);
			log.debug(
					"Pre-refresh check: name={}, redisKey={}, ttlSec={}, configuredTtlSec={}",
					getName(),
					cacheKey,
					ttl,
					configuredTtl);
			if (ttl >= 0 && shouldPreRefresh(ttl, configuredTtl)) {
				ReentrantLock lock = registry.obtainLock(getName(), key);
				executor.execute(
						() -> {
							try {
								// First check to avoid unnecessary refresh
								long ttl2 = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
								long configuredTtl2 = resolveConfiguredTtlSeconds(valueWrapper.get(), key);
								if (ttl2 < 0 || !shouldPreRefresh(ttl2, configuredTtl2)) {
									log.debug(
											"Pre-refresh skipped after first-check: name={}, redisKey={}, ttl2Sec={}, configuredTtl2Sec={}",
											getName(),
											cacheKey,
											ttl2,
											configuredTtl2);
									return;
								}

								String distKey = "cache:refresh:" + cacheKey;
								long leaseTimeSec = Math.max(5L, Math.min(30L, ttl2));
								log.debug(
										"Pre-refresh attempt: name={}, redisKey={}, distKey={}, leaseTimeSec={}",
										getName(),
										cacheKey,
										distKey,
										leaseTimeSec);

								boolean executed = LockUtils.runWithLocalTryThenDistTry(
										lock,
										distributedLock,
										distKey,
										0L,
										leaseTimeSec,
										TimeUnit.SECONDS,
										() -> {
											// Second check (after acquiring both locks)
											long t3 = redisTemplate.getExpire(
													cacheKey, TimeUnit.SECONDS);
											long c3 = resolveConfiguredTtlSeconds(
													valueWrapper.get(), key);
											if (t3 < 0 || !shouldPreRefresh(t3, c3)) {

												log.debug(
														"Pre-refresh skipped after second-check: name={}, redisKey={}, t3Sec={}, c3Sec={}",
														getName(),
														cacheKey,
														t3,
														c3);

												return;
											}

											log.debug(
													"Pre-refresh acquired locks, start refresh: name={}, redisKey={}",
													getName(),
													cacheKey);

											registry.get(getName(), key)
													.ifPresent(
															invocation -> doRefresh(
																	invocation,
																	key,
																	cacheKey,
																	t3));
										});

								log.debug(
										"Pre-refresh executed={} name={}, redisKey={}, distKey={}, leaseTimeSec={}",
										executed,
										getName(),
										cacheKey,
										distKey,
										leaseTimeSec);

							} catch (Exception ex) {
								log.warn(
										"Cache pre-refresh error, name={}, key={}, err= {}",
										getName(),
										cacheKey,
										ex.getMessage());
							}
						});
			}
		} catch (Exception e) {
			log.debug("Skip pre-refresh due to error: {}", e.getMessage());
		}
		// Unpacking is handled by fromStoreValue, return directly
		return valueWrapper;
	}

	private boolean shouldPreRefresh(long remainingTtlSec, long configuredTtlSec) {
		if (remainingTtlSec <= 0 || configuredTtlSec <= 0)
			return false;
		long threshold = Math.max(1L, (long) Math.floor(configuredTtlSec * 0.20d));
		return remainingTtlSec <= threshold;
	}

	private long resolveConfiguredTtlSeconds(@Nullable Object value, @NonNull Object key) {
		try {
			if (cacheConfiguration != null) {
				if (value == null)
					return -1L; // Avoid passing @Nullable to an API that requires @NonNull
				Duration d = cacheConfiguration.getTtlFunction().getTimeToLive(value, key);
				if (!d.isNegative() && !d.isZero()) {
					return d.getSeconds();
				}
			}
		} catch (Exception ignore) {
		}
		return -1L;
	}

	private void doRefresh(CachedInvocation invocation, Object key, String cacheKey, long ttl) {
		try {
			Object refreshed = invocation.invoke();
			// Use wrapper to write back and reset TTL
			this.put(key, refreshed);
			log.info(
					"Refreshed cache, name={}, redisKey={}, oldTtlSec={}, refreshedType={}",
					getName(),
					cacheKey,
					ttl,
					refreshed == null ? "null" : refreshed.getClass().getSimpleName());
		} catch (Throwable ex) {
			log.warn(
					"Failed to refresh cache, name={}, redisKey={}, err={}",
					getName(),
					cacheKey,
					ex.getMessage());
		}
	}

	@Override
	public void put(@NonNull Object key, @Nullable Object value) {
		Object toStore = wrapIfMataAbsent(value, key);
		super.put(key, toStore);
		// Add key to Bloom filter after successful write (if value is not null)
		try {
			if (value != null) {
				String membershipKey = String.valueOf(key);
				cachePenetration.addIfEnabled(getName(), membershipKey);
			}
		} catch (Exception ignore) {
		}
		// Avalanche protection: unified call
		applyLitteredExpire(key, toStore);
		log.debug(
				"Put cache entry: name={}, key={}, valueType={}",
				getName(),
				key,
				value == null ? "null" : value.getClass().getSimpleName());
	}

	@Override
	@NonNull
	public ValueWrapper putIfAbsent(@NonNull Object key, @Nullable Object value) {
		Object toStore = wrapIfMataAbsent(value, key);
		boolean firstInsert = wasKeyAbsentBeforePut(key);
		ValueWrapper previous = super.putIfAbsent(key, toStore);
		if (firstInsert) {
			try {
				if (value != null) {
					String membershipKey = String.valueOf(key);
					cachePenetration.addIfEnabled(getName(), membershipKey);
				}
			} catch (Exception ignore) {
			}
			applyLitteredExpire(key, toStore);
		}
		log.debug(
				"PutIfAbsent: name={}, key={}, inserted={}",
				getName(),
				key,
				firstInsert);
		return previous;
	}

	@Override
	@Nullable
	public <T> T get(@NonNull Object key, @Nullable Class<T> type) {
		ValueWrapper wrapper = this.get(key);
		if (wrapper == null)
			return null;
		Object val = wrapper.get();
		if (type == null || type == Object.class) {
			@SuppressWarnings("unchecked")
			T casted = (T) val;
			return casted;
		}
		return (type.isInstance(val) ? type.cast(val) : null);
	}

	@Override
	@NonNull
	public <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
		String cacheKey = createCacheKey(key);
		String membershipKey = String.valueOf(key);
		ReentrantLock localLock = registry.obtainLock(getName(), key);
		String distKey = "cache:load:" + cacheKey;
		long leaseTimeSec = 30L; // Defensive lease time to prevent permanent lock occupation
		log.debug(
				"First-load attempt: name={}, redisKey={}, distKey={}, leaseTimeSec={}",
				getName(),
				cacheKey,
				distKey,
				leaseTimeSec);

		// Bloom pre-check: if enabled and "definitely does not exist", throw
		// ValueRetrievalException to block penetration
		if (cachePenetration.isEnabled(getName())) {
			boolean mightContain = cachePenetration.mightContain(getName(), membershipKey);
			if (!mightContain) {
				log.debug("Bloom blocked load: name={}, bloomKey={}", getName(), membershipKey);
				throw new Cache.ValueRetrievalException(
						key,
						valueLoader,
						new NoSuchElementException("Blocked by Bloom filter: " + membershipKey));
			}
		}
		try {
			T result = cacheBreakdown.loadWithProtection(
					getName(),
					distKey,
					localLock,
					leaseTimeSec,
					TimeUnit.SECONDS,
					() -> {
						ValueWrapper w = super.get(key);
						if (w != null) {
							Object v = w.get();
							if (v != null) {
								// Compensate by adding to Bloom if cache hit
								try {
									cachePenetration.addIfEnabled(getName(), membershipKey);
								} catch (Exception ignore) {
								}
								@SuppressWarnings("unchecked")
								T casted = (T) v;
								return casted;
							}
						}
						return null;
					},
					valueLoader::call,
					(val) -> this.put(key, val));
			log.debug(
					"First-load finished: name={}, redisKey={}, distKey={}",
					getName(),
					cacheKey,
					distKey);
			return result;
		} catch (Exception ex) {
			throw new Cache.ValueRetrievalException(key, valueLoader, ex);
		}
	}

	@Override
	public void evict(@NonNull Object key) {
		String cacheKey = createCacheKey(key);
		ReentrantLock localLock = evictRegistry.obtainLock(getName(), key);
		String distKey = "cache:evict:" + cacheKey;
		long leaseTimeSec = 10L;
		boolean executed = LockUtils.runWithLocalTryThenDistTry(
				localLock,
				distributedLock,
				distKey,
				0L,
				leaseTimeSec,
				TimeUnit.SECONDS,
				() -> {
					doEvictInternal(key);
					scheduleSecondDeleteForKey(key);
				});
		if (!executed) {
			// Fallback: try to execute even if lock acquisition fails to improve
			// availability
			doEvictInternal(key);
			scheduleSecondDeleteForKey(key);
		}
		log.info(
				"Evicted cache key, name={}, redisKey={}, distKey={}, executed={}, secondDeleteScheduled=true",
				getName(),
				cacheKey,
				distKey,
				executed);
	}

	@Override
	public void clear() {
		String distKey = "cache:evictAll:" + getName();
		ReentrantLock localLock = evictRegistry.obtainLock(getName(), "*");
		long leaseTimeSec = 15L;
		boolean executed = LockUtils.runWithLocalTryThenDistTry(
				localLock,
				distributedLock,
				distKey,
				0L,
				leaseTimeSec,
				TimeUnit.SECONDS,
				() -> {
					doClearInternal();
					scheduleSecondClear();
				});
		if (!executed) {
			doClearInternal();
			scheduleSecondClear();
		}
		log.info("Cleared cache, name={}, secondClearScheduled=true", getName());
	}

	private byte[] createAndConvertCacheKey(Object key) {
		return serializeCacheKey(createCacheKey(key));
	}

	private Object wrapIfMataAbsent(@Nullable Object value, @NonNull Object key) {
		if (value == null)
			return null;
		if (value instanceof CacheMata)
			return value;
		long ttlSecs = -1L;
		try {
			if (cacheConfiguration != null) {
				{
					Duration d = cacheConfiguration.getTtlFunction().getTimeToLive(value, key);
					if (!d.isNegative() && !d.isZero()) {
						ttlSecs = d.getSeconds();
					}
				}
			}
		} catch (Exception ignored) {
		}
		// Avalanche protection: jitter TTL through strategy class (randomly shorten)
		long effectiveTtl = ttlSecs > 0 ? cacheAvalanche.jitterTtlSeconds(ttlSecs) : ttlSecs;
		// No longer calculate local expiration time, only use TTL in metadata, managed
		// uniformly by Redis
		return CacheMata.builder().ttl(effectiveTtl).value(value).build();
	}

	// Unpacking responsibility is centralized in fromStoreValue()

	@Override
	protected Object fromStoreValue(@Nullable Object storeValue) {
		Object v = super.fromStoreValue(storeValue);
		if (v instanceof CacheMata) {
			return ((CacheMata) v).getValue();
		}
		return v;
	}

	/** Unified setting of jittered expiration time to avoid duplicate code. */
	private void applyLitteredExpire(Object key, Object toStore) {
		try {
			if (toStore instanceof CacheMata meta && meta.getTtl() > 0) {
				String cacheKey = createCacheKey(key);
				long seconds = meta.getTtl();
				if (seconds > 0) {
					redisTemplate.expire(cacheKey, seconds, TimeUnit.SECONDS);
				}
			}
		} catch (Exception ignore) {
		}
	}

	/**
	 * Check if the key was absent before putIfAbsent (-2 means the key does not
	 * exist in Redis).
	 */
	private boolean wasKeyAbsentBeforePut(Object key) {
		try {
			Long ttl = redisTemplate.getExpire(createCacheKey(key), TimeUnit.SECONDS);
			return ttl == -2L;
		} catch (Exception ignore) {
			return false;
		}
	}

	/** Immediately delete single key and clean up local registry. */
	private void doEvictInternal(@NonNull Object key) {
		try {
			getCacheWriter().remove(getName(), createAndConvertCacheKey(key));
		} finally {
			try {
				registry.remove(getName(), key);
			} catch (Exception ignore) {
			}
			try {
				evictRegistry.remove(getName(), key);
			} catch (Exception ignore) {
			}
		}
	}

	/**
	 * Delayed secondary deletion (single key), and attempt to acquire lock again
	 * during second deletion to avoid race conditions.
	 */
	private void scheduleSecondDeleteForKey(@NonNull Object key) {
		Executor delayed = CompletableFuture.delayedExecutor(
				DOUBLE_DELETE_DELAY_MS, TimeUnit.MILLISECONDS, executor);
		CompletableFuture.runAsync(
				() -> {
					String cacheKey = createCacheKey(key);
					String distKey = "cache:evict:" + cacheKey;
					ReentrantLock localLock = evictRegistry.obtainLock(getName(), key);
					log.debug(
							"Second-delete attempt: name={}, redisKey={}, distKey={}",
							getName(),
							cacheKey,
							distKey);
					LockUtils.runWithLocalTryThenDistTry(
							localLock,
							distributedLock,
							distKey,
							0L,
							5L,
							TimeUnit.SECONDS,
							() -> doEvictInternal(key));
				},
				delayed);
	}

	/** Immediate full cleanup and clean up local registry. */
	private void doClearInternal() {
		try {
			super.clear();
		} finally {
			try {
				registry.removeAll(getName());
			} catch (Exception ignore) {
			}
			try {
				evictRegistry.removeAll(getName());
			} catch (Exception ignore) {
			}
		}
	}

	/**
	 * Delayed secondary full deletion, and attempt to acquire lock again during
	 * second deletion to avoid race conditions.
	 */
	private void scheduleSecondClear() {
		Executor delayed = CompletableFuture.delayedExecutor(
				DOUBLE_DELETE_DELAY_MS, TimeUnit.MILLISECONDS, executor);
		CompletableFuture.runAsync(
				() -> {
					String distKey = "cache:evictAll:" + getName();
					ReentrantLock localLock = evictRegistry.obtainLock(getName(), "*");
					log.debug(
							"Second-clear attempt: name={}, distKey={}",
							getName(),
							distKey);
					LockUtils.runWithLocalTryThenDistTry(
							localLock,
							distributedLock,
							distKey,
							0L,
							5L,
							TimeUnit.SECONDS,
							this::doClearInternal);
				},
				delayed);
	}
}
