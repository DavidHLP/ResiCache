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
import com.david.spring.cache.redis.strategy.cacheable.context.CacheContext;
import com.david.spring.cache.redis.strategy.cacheable.context.CacheableContext;
import com.david.spring.cache.redis.strategy.cacheable.context.ExecutionContext;
import com.david.spring.cache.redis.strategy.cacheable.context.ProtectionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Objects;
import java.util.concurrent.*;
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
		// 尝试获取调用上下文以提供更详细的配置信息
		CachedInvocationContext invocationContext = null;
		try {
			CachedInvocation invocation = registry.get(getName(), key).orElse(null);
			invocationContext = (invocation != null) ? invocation.getCachedInvocationContext() : null;
		} catch (Exception ignored) {
			// 忽略异常，使用默认配置
		}

		// 构建增强的核心缓存上下文
		CacheContext cacheContext = CacheContext.builder()
				.key(key)
				.cacheName(getName())
				.parentCache(this)
				.redisTemplate(redisTemplate)
				.cacheConfiguration(cacheConfiguration)
				.invocationContext(invocationContext)
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

		// 创建临时缓存上下文来获取配置信息
		CachedInvocationContext invocationContext = null;
		try {
			CachedInvocation invocation = registry.get(getName(), key).orElse(null);
			invocationContext = (invocation != null) ? invocation.getCachedInvocationContext() : null;
		} catch (Exception ignored) {
			// 忽略异常
		}

		CacheContext cacheContext = CacheContext.builder()
				.key(key)
				.cacheName(getName())
				.parentCache(this)
				.redisTemplate(redisTemplate)
				.cacheConfiguration(cacheConfiguration)
				.invocationContext(invocationContext)
				.build();

		// 获取有效TTL
		long effectiveTtlSecs;
		if (value != null) {
			// 优先使用动态TTL计算
			effectiveTtlSecs = cacheContext.getDynamicTtlSeconds(value);
			if (effectiveTtlSecs <= 0) {
				// 回退到带抖动的TTL
				effectiveTtlSecs = cacheContext.getEffectiveTtlSeconds();
			}
		} else {
			effectiveTtlSecs = cacheContext.getEffectiveTtlSeconds();
		}

		// 如果没有配置TTL抖动，使用全局雪崩保护
		if (effectiveTtlSecs > 0 &&
			(invocationContext == null || !invocationContext.randomTtl())) {
			effectiveTtlSecs = cacheAvalanche.jitterTtlSeconds(effectiveTtlSecs);
		}

		// 处理null值缓存
		if (value == null) {
			if (cacheContext.shouldCacheNullValues()) {
				return CacheMata.builder().ttl(effectiveTtlSecs).value(null).build();
			}
			return null;
		}

		// 包装非null值
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
