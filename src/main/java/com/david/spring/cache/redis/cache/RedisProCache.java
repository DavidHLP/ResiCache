package com.david.spring.cache.redis.cache;

import com.david.spring.cache.redis.locks.DistributedLock;
import com.david.spring.cache.redis.locks.LockUtils;
import com.david.spring.cache.redis.meta.CacheMata;
import com.david.spring.cache.redis.protection.CacheAvalanche;
import com.david.spring.cache.redis.protection.CacheBreakdown;
import com.david.spring.cache.redis.protection.CachePenetration;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.impl.CacheInvocationRegistry;
import com.david.spring.cache.redis.registry.impl.EvictInvocationRegistry;
import com.david.spring.cache.redis.strategy.cacheable.CacheableStrategyManager;
import com.david.spring.cache.redis.strategy.cacheable.context.CacheableContext;
import com.david.spring.cache.redis.strategy.cacheable.context.CacheContext;
import com.david.spring.cache.redis.strategy.cacheable.context.ProtectionContext;
import com.david.spring.cache.redis.strategy.cacheable.context.ExecutionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ThreadLocalRandom;

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
	/** 缓存获取策略管理器 */
	private final CacheableStrategyManager strategyManager;

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
			CacheAvalanche cacheAvalanche,
			CacheableStrategyManager strategyManager) {
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
		this.strategyManager = Objects.requireNonNull(strategyManager);
		log.info("RedisProCache initialized with strategy manager, name={}", name);
	}

	@Override
	@Nullable
	public ValueWrapper get(@NonNull Object key) {
		log.debug("Getting cache value using strategy manager for key: {}", key);

		// 创建缓存获取上下文
		CacheableContext<Object> context = createCacheGetContext(key);

		// 使用策略管理器执行缓存获取
		return strategyManager.get(context);
	}

	/**
	 * 直接调用父类的 get 方法，避免策略循环调用
	 *
	 * @param key 缓存键
	 * @return 缓存值包装器
	 */
	public ValueWrapper getFromParent(@NonNull Object key) {
		return super.get(key);
	}

	/**
	 * 创建缓存获取上下文
	 *
	 * @param key 缓存键
	 * @return 缓存获取上下文
	 */
	private CacheableContext<Object> createCacheGetContext(Object key) {
		// 构建核心缓存上下文
		CacheContext cacheContext = CacheContext.builder()
				.key(key)
				.cacheName(getName())
				.parentCache(this)
				.redisTemplate(redisTemplate)
				.cacheConfiguration(cacheConfiguration)
				.build();

		// 构建保护机制上下文
		ProtectionContext protectionContext = ProtectionContext.builder()
				.distributedLock(distributedLock)
				.cachePenetration(cachePenetration)
				.cacheBreakdown(cacheBreakdown)
				.cacheAvalanche(cacheAvalanche)
				.build();

		// 构建执行上下文
		ExecutionContext executionContext = ExecutionContext.builder()
				.executor(executor)
				.registry(registry)
				.build();

		// 组合所有上下文
		return CacheableContext.builder()
				.cacheContext(cacheContext)
				.protectionContext(protectionContext)
				.executionContext(executionContext)
				.build();
	}

	@Override
	public void put(@NonNull Object key, @Nullable Object value) {
		Object toStore = wrapIfMataAbsent(value, key);
		if (toStore == null) {
			log.debug("Skipping cache put for key: {} due to null policy", key);
			return;
		}
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
		if (toStore == null) {
			log.debug("Skipping cache putIfAbsent for key: {} due to null policy", key);
			// do not put; return existing value if any
			return super.get(key);
		}
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
		log.debug("Getting cache value with value loader using strategy manager for key: {}", key);

		// 创建缓存获取上下文
		CacheableContext<Object> context = createCacheGetContext(key);

		// 使用策略管理器执行带值加载器的缓存获取
		return Objects.requireNonNull(strategyManager.get(context, valueLoader));
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
        // Allow passing through existing metadata
        if (value instanceof CacheMata) {
            return value;
        }

        // Resolve per-invocation context if available
        CachedInvocationContext cic = null;
        try {
            CachedInvocation invocation = registry.get(getName(), key).orElse(null);
            cic = (invocation != null) ? invocation.getCachedInvocationContext() : null;
        } catch (Exception ignore) {
        }

        // Determine base TTL in seconds
        long baseTtlSecs = -1L;
        try {
            if (cic != null && cic.ttl() > 0) {
                baseTtlSecs = Math.max(0L, cic.ttl() / 1000);
            } else if (cacheConfiguration != null) {
                if (value != null) {
                    Duration d = cacheConfiguration.getTtlFunction().getTimeToLive(value, key);
                    if (d != null && !d.isNegative() && !d.isZero()) {
                        baseTtlSecs = d.getSeconds();
                    }
                } else {
                    // null value: fall back to default TTL
                    Duration d = cacheConfiguration.getTtl();
                    if (d != null && !d.isNegative() && !d.isZero()) {
                        baseTtlSecs = d.getSeconds();
                    }
                }
            }
        } catch (Exception ignore) {
        }

        // Compute effective TTL considering jitter settings
        long effectiveTtlSecs = baseTtlSecs;
        if (baseTtlSecs > 0) {
            if (cic != null) {
                if (!cic.randomTtl()) {
                    // disable jitter per invocation
                    effectiveTtlSecs = baseTtlSecs;
                } else if (cic.variance() > 0.0f) {
                    double var = Math.min(0.99d, Math.max(0.0d, cic.variance()));
                    double ratio = (var > 0.0d) ? ThreadLocalRandom.current().nextDouble(0.0d, var) : 0.0d;
                    long jittered = (long) Math.floor(baseTtlSecs * (1.0d - ratio));
                    effectiveTtlSecs = Math.max(1L, jittered);
                } else {
                    // fallback to global avalanche jitter
                    effectiveTtlSecs = cacheAvalanche.jitterTtlSeconds(baseTtlSecs);
                }
            } else {
                // no per-invocation context; use global jitter
                effectiveTtlSecs = cacheAvalanche.jitterTtlSeconds(baseTtlSecs);
            }
        }

        // Handle null value caching per invocation
        if (value == null) {
            if (cic != null && cic.cacheNullValues()) {
                return CacheMata.builder().ttl(effectiveTtlSecs).value(null).build();
            }
            return null;
        }

        // Wrap non-null value with metadata including TTL
        return CacheMata.builder().ttl(effectiveTtlSecs).value(value).build();
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
