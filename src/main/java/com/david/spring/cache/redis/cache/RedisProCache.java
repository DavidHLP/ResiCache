package com.david.spring.cache.redis.cache;

import com.david.spring.cache.redis.lock.DistributedLock;
import com.david.spring.cache.redis.lock.LockUtils;
import com.david.spring.cache.redis.protection.CacheBreakdown;
import com.david.spring.cache.redis.protection.CachePenetration;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import com.david.spring.cache.redis.registry.EvictInvocationRegistry;
import com.david.spring.cache.redis.strategy.CacheFetchStrategy;
import com.david.spring.cache.redis.strategy.CacheFetchStrategyManager;
import com.david.spring.cache.redis.strategy.CacheOperationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

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
	private final CacheFetchStrategyManager strategyManager;
	private final CacheOperationService cacheOperationService;

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
			CacheFetchStrategyManager strategyManager,
			CacheOperationService cacheOperationService) {
		super(name, cacheWriter, cacheConfiguration);
		this.redisTemplate = Objects.requireNonNull(redisTemplate);
		this.registry = Objects.requireNonNull(registry);
		this.evictRegistry = Objects.requireNonNull(evictRegistry);
		this.executor = Objects.requireNonNull(executor);
		this.cacheConfiguration = Objects.requireNonNull(cacheConfiguration);
		this.distributedLock = Objects.requireNonNull(distributedLock);
		this.cachePenetration = Objects.requireNonNull(cachePenetration);
		this.cacheBreakdown = Objects.requireNonNull(cacheBreakdown);
		this.strategyManager = Objects.requireNonNull(strategyManager);
		this.cacheOperationService = Objects.requireNonNull(cacheOperationService);
	}

	@Override
	@Nullable
	public ValueWrapper get(@NonNull Object key) {
		ValueWrapper valueWrapper = super.get(key);

		// 获取当前缓存调用的上下文信息
		CachedInvocation invocation = registry.get(getName(), key).orElse(null);
		if (invocation == null || invocation.getCachedInvocationContext() == null) {
			// 如果没有调用上下文，直接返回值
			return valueWrapper;
		}

		String cacheKey = createCacheKey(key);

		// 创建策略回调实现
		CacheFetchStrategy.CacheFetchCallback callback = new CacheFetchStrategy.CacheFetchCallback() {
			@Override
			public ValueWrapper getBaseValue(Object key) {
				return RedisProCache.super.get(key);
			}

			@Override
			public void refresh(CachedInvocation invocation, Object key, String cacheKey, long ttl) {
				CacheOperationService.CacheRefreshCallback callback = new CacheOperationService.CacheRefreshCallback() {
					@Override
					public void putCache(Object key, Object value) {
						RedisProCache.this.put(key, value);
					}

					@Override
					public String getCacheName() {
						return getName();
					}
				};
				cacheOperationService.doRefresh(invocation, key, cacheKey, ttl, callback);
			}

			@Override
			public long resolveConfiguredTtlSeconds(Object value, Object key) {
				return cacheOperationService.resolveConfiguredTtlSeconds(value, key, cacheConfiguration);
			}

			@Override
			public boolean shouldPreRefresh(long ttl, long configuredTtl) {
				return cacheOperationService.shouldPreRefresh(ttl, configuredTtl);
			}
		};

		// 创建策略执行上下文
		CacheFetchStrategy.CacheFetchContext context = new CacheFetchStrategy.CacheFetchContext(
				getName(),
				key,
				cacheKey,
				valueWrapper,
				invocation,
				invocation.getCachedInvocationContext(),
				redisTemplate,
				callback
		);

		// 使用策略管理器执行获取策略
		return strategyManager.fetch(context);
	}

	// TTL、刷新、包装等方法已下沉到 CacheOperationService

	@Override
	public void put(@NonNull Object key, @Nullable Object value) {
		Object toStore = cacheOperationService.wrapIfMataAbsent(value, key, cacheConfiguration);
		super.put(key, toStore);
		// 成功写入后将 key 加入布隆过滤器（值非空时）
		try {
			if (value != null) {
				String membershipKey = String.valueOf(key);
				cachePenetration.addIfEnabled(getName(), membershipKey);
			}
		} catch (Exception ignore) {
		}
		// 雪崩保护：统一调用
		String cacheKey = createCacheKey(key);
		cacheOperationService.applyLitteredExpire(key, toStore, cacheKey, redisTemplate);
	}

	@Override
	@NonNull
	public ValueWrapper putIfAbsent(@NonNull Object key, @Nullable Object value) {
		Object toStore = cacheOperationService.wrapIfMataAbsent(value, key, cacheConfiguration);
		String cacheKey = createCacheKey(key);
		boolean firstInsert = cacheOperationService.wasKeyAbsentBeforePut(cacheKey, redisTemplate);
		ValueWrapper previous = super.putIfAbsent(key, toStore);
		if (firstInsert) {
			try {
				if (value != null) {
					String membershipKey = String.valueOf(key);
					cachePenetration.addIfEnabled(getName(), membershipKey);
				}
			} catch (Exception ignore) {
			}
			cacheOperationService.applyLitteredExpire(key, toStore, cacheKey, redisTemplate);
		}
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
		long leaseTimeSec = 30L; // 防御性租期，避免锁永久占用
		log.debug(
				"First-load attempt: name={}, redisKey={}, distKey={}, leaseTimeSec={}",
				getName(),
				cacheKey,
				distKey,
				leaseTimeSec);

		// Bloom 预检：若启用且断言“不可能存在”，直接抛出 ValueRetrievalException，阻断穿透
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
								// 命中缓存则补偿加入 Bloom
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
			// 降级：未能加锁也尝试执行，以提升可用性
			doEvictInternal(key);
			scheduleSecondDeleteForKey(key);
		}
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
	}

	private byte[] createAndConvertCacheKey(Object key) {
		return serializeCacheKey(createCacheKey(key));
	}

	// 包装方法已下沉到 CacheOperationService

	@Override
	protected Object fromStoreValue(@Nullable Object storeValue) {
		Object v = super.fromStoreValue(storeValue);
		return cacheOperationService.fromStoreValue(v);
	}

	// 过期时间设置和存在性检查方法已下沉到 CacheOperationService

	/** 立即删除单键并清理本地注册。 */
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

	/** 延迟二次删除（单键），并在二次删除时再次尝试加锁以避免竞态。 */
	private void scheduleSecondDeleteForKey(@NonNull Object key) {
		Executor delayed = CompletableFuture.delayedExecutor(
				DOUBLE_DELETE_DELAY_MS, TimeUnit.MILLISECONDS, executor);
		CompletableFuture.runAsync(
				() -> {
					String cacheKey = createCacheKey(key);
					String distKey = "cache:evict:" + cacheKey;
					ReentrantLock localLock = evictRegistry.obtainLock(getName(), key);
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

	/** 立即全量清理并清理本地注册。 */
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

	/** 延迟二次全量删除，并在二次删除时再次尝试加锁以避免竞态。 */
	private void scheduleSecondClear() {
		Executor delayed = CompletableFuture.delayedExecutor(
				DOUBLE_DELETE_DELAY_MS, TimeUnit.MILLISECONDS, executor);
		CompletableFuture.runAsync(
				() -> {
					String distKey = "cache:evictAll:" + getName();
					ReentrantLock localLock = evictRegistry.obtainLock(getName(), "*");
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
