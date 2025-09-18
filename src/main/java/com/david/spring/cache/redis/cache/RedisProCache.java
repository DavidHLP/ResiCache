package com.david.spring.cache.redis.cache;

import com.david.spring.cache.redis.meta.CacheMata;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.registry.impl.CacheInvocationRegistry;
import com.david.spring.cache.redis.strategy.cacheable.CacheableStrategy;
import com.david.spring.cache.redis.strategy.cacheable.CacheableStrategyManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;

@Slf4j
public class RedisProCache extends RedisCache {

	/**
	 * ThreadLocal 用于在 toStoreValue 方法中获取当前的 key
	 */
	private static final ThreadLocal<Object> CURRENT_KEY = new ThreadLocal<>();
	private final CacheableStrategyManager strategyManager;
	private final CacheInvocationRegistry invocationRegistry;

	public RedisProCache(
			String name,
			RedisCacheWriter cacheWriter,
			RedisCacheConfiguration cacheConfiguration,
			CacheableStrategyManager strategyManager,
			CacheInvocationRegistry invocationRegistry) {
		super(name, cacheWriter, cacheConfiguration);
		this.strategyManager = strategyManager;
		this.invocationRegistry = invocationRegistry;
		log.info("RedisProCache initialized with strategy manager and processor manager, name={}", name);
	}

	/**
	 * 执行策略操作的通用方法
	 */
	private <T> T executeWithStrategy(Object key, StrategyOperation<T> operation, ParentOperation<T> fallback) {
		CachedInvocation cachedInvocation = getCachedInvocation(key);
		if (cachedInvocation != null) {
			CacheableStrategy<T> strategy = strategyManager.selectStrategy(cachedInvocation);
			if (strategy != null) {
				log.debug("Using strategy: {} for cache operation", strategy.getStrategyName());
				return operation.execute(strategy, cachedInvocation);
			}
		}
		log.debug("Using default parent method for key: {}", key);
		return fallback.execute();
	}

	@Override
	@Nullable
	public ValueWrapper get(@NonNull Object key) {
		log.debug("Getting cache value for key: {}", key);
		ValueWrapper result = executeWithStrategy(key,
				(strategy, cachedInvocation) -> strategy.get(this, key, cachedInvocation),
				() -> super.get(key));

		// 包装结果以支持 CacheMata 自动解包
		return result != null ? new CacheMatAwareValueWrapper(result) : null;
	}

	/**
	 * 直接调用父类的 get 方法，避免策略循环调用
	 *
	 * @param key 缓存键
	 * @return 缓存值包装器
	 */
	public ValueWrapper getFromParent(@NonNull Object key) {
		ValueWrapper result = super.get(key);
		return result != null ? new CacheMatAwareValueWrapper(result) : null;
	}

	/**
	 * 直接调用父类的 put 方法，避免策略循环调用
	 * 注意：这里仍然会调用我们重写的 toStoreValue 方法进行包装
	 *
	 * @param key   缓存键
	 * @param value 缓存值
	 */
	public void putFromParent(@NonNull Object key, @Nullable Object value) {
		log.debug("putFromParent called: key={}, value={}, valueType={}",
				key, value, value != null ? value.getClass().getSimpleName() : "null");

		// 直接包装为 CacheMata 而不依赖 toStoreValue
		Object wrappedValue = wrapValueAsCacheMata(value, key);
		log.info("Wrapped value as CacheMata in putFromParent: wrappedType={}",
				wrappedValue != null ? wrappedValue.getClass().getSimpleName() : "null");

		try {
			// 设置当前 key 到 ThreadLocal
			CURRENT_KEY.set(key);
			// 调用父类的 put 方法，传入包装后的 CacheMata
			super.put(key, wrappedValue);
			log.debug("putFromParent completed for key: {}", key);
		} finally {
			// 清理 ThreadLocal
			CURRENT_KEY.remove();
		}
	}

	/**
	 * 直接调用父类的 putIfAbsent 方法，避免策略循环调用
	 *
	 * @param key   缓存键
	 * @param value 缓存值
	 * @return 缓存值包装器
	 */
	@NonNull
	public ValueWrapper putIfAbsentFromParent(@NonNull Object key, @Nullable Object value) {
		// 直接包装为 CacheMata
		Object wrappedValue = wrapValueAsCacheMata(value, key);
		log.info("Wrapped value as CacheMata in putIfAbsentFromParent: wrappedType={}",
				wrappedValue != null ? wrappedValue.getClass().getSimpleName() : "null");

		ValueWrapper result = super.putIfAbsent(key, wrappedValue);
		return new CacheMatAwareValueWrapper(result);
	}

	/**
	 * 直接调用父类的 evict 方法，避免策略循环调用
	 *
	 * @param key 缓存键
	 */
	public void evictFromParent(@NonNull Object key) {
		super.evict(key);
	}

	@Override
	public void put(@NonNull Object key, @Nullable Object value) {
		log.debug("Putting cache value for key: {}", key);

		// 直接包装为 CacheMata
		Object wrappedValue = wrapValueAsCacheMata(value, key);
		log.info("Wrapped value as CacheMata in put: wrappedType={}",
				wrappedValue != null ? wrappedValue.getClass().getSimpleName() : "null");

		try {
			// 设置当前 key 到 ThreadLocal
			CURRENT_KEY.set(key);
			executeWithStrategy(key,
					(strategy, cachedInvocation) -> {
						strategy.put(this, key, wrappedValue, cachedInvocation);
						return null;
					},
					() -> {
						super.put(key, wrappedValue);
						return null;
					});
		} finally {
			// 清理 ThreadLocal
			CURRENT_KEY.remove();
		}
	}

	@Override
	@NonNull
	public ValueWrapper putIfAbsent(@NonNull Object key, @Nullable Object value) {
		log.debug("Putting cache value if absent for key: {}", key);

		// 直接包装为 CacheMata
		Object wrappedValue = wrapValueAsCacheMata(value, key);
		log.info("Wrapped value as CacheMata in putIfAbsent: wrappedType={}",
				wrappedValue != null ? wrappedValue.getClass().getSimpleName() : "null");

		try {
			// 设置当前 key 到 ThreadLocal
			CURRENT_KEY.set(key);
			ValueWrapper result = executeWithStrategy(key,
					(strategy, cachedInvocation) -> strategy.putIfAbsent(this, key, wrappedValue, cachedInvocation),
					() -> super.putIfAbsent(key, wrappedValue));
			return new CacheMatAwareValueWrapper(result);
		} finally {
			// 清理 ThreadLocal
			CURRENT_KEY.remove();
		}
	}

	@Override
	@Nullable
	public <T> T get(@NonNull Object key, @Nullable Class<T> type) {
		ValueWrapper wrapper = this.get(key);
		if (wrapper == null)
			return null;
		Object val = wrapper.get();

		// 如果是 CacheMata，提取原始值
		if (val instanceof CacheMata cacheMata) {
			val = cacheMata.getValue();
		}

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
		log.debug("Getting cache value with loader for key: {}", key);
		return executeWithStrategy(key,
				(strategy, cachedInvocation) -> strategy.get(this, key, valueLoader, cachedInvocation),
				() -> super.get(key, valueLoader));
	}

	@Override
	public void evict(@NonNull Object key) {
		log.debug("Evicting cache value for key: {}", key);
		executeWithStrategy(key,
				(strategy, cachedInvocation) -> {
					strategy.evict(this, key, cachedInvocation);
					return null;
				},
				() -> {
					super.evict(key);
					return null;
				});
	}

	@Override
	public void clear() {
		log.debug("Clearing cache: {}", getName());
		super.clear();
	}

	@Override
	protected Object fromStoreValue(@Nullable Object storeValue) {
		Object v = super.fromStoreValue(storeValue);
		if (v instanceof CacheMata cacheMata) {
			// 过期检查
			if (cacheMata.isExpired()) {
				log.debug("Cache metadata expired for store value, returning null");
				return null;
			}

			// 访问统计处理
			cacheMata.incrementVisitTimes();

			// 返回 CacheMata 对象而不是原始值
			return cacheMata;
		}
		return v;
	}

	/**
	 * 带 key 信息的 fromStoreValue 方法，提供完整的上下文
	 */
	protected Object fromStoreValueWithKey(@Nullable Object storeValue, @NonNull Object key) {
		Object v = super.fromStoreValue(storeValue);
		if (v instanceof CacheMata cacheMata) {
			String cacheName = getName();

			// 过期检查
			if (cacheMata.isExpired()) {
				log.debug("Cache metadata expired for key: {}, returning null", key);
				return null;
			}

			// 访问统计处理
			cacheMata.incrementVisitTimes();

			// 返回 CacheMata 对象而不是原始值
			return cacheMata;
		}
		return v;
	}

	/**
	 * 将值包装为 CacheMata 对象用于存储
	 *
	 * @param value 原始值
	 * @return 包装后的 CacheMata 对象
	 */
	@NonNull
	@Override
	protected Object toStoreValue(@Nullable Object value) {
		// 从 ThreadLocal 获取当前的 key，如果没有则使用 "unknown"
		Object currentKey = CURRENT_KEY.get();
		if (currentKey == null) {
			currentKey = "unknown";
		}
		log.info("toStoreValue called: key={}, value={}, valueType={}",
				currentKey, value, value != null ? value.getClass().getSimpleName() : "null");
		Object result = toStoreValueWithKey(value, currentKey);
		log.info("toStoreValue result: resultType={}", result != null ? result.getClass().getSimpleName() : "null");
		return result != null ? result : super.toStoreValue(null);
	}

	/**
	 * 带 key 信息的 toStoreValue 方法，提供完整的上下文
	 *
	 * @param value 原始值
	 * @param key   缓存键
	 * @return 包装后的 CacheMata 对象
	 */
	protected Object toStoreValueWithKey(@Nullable Object value, @NonNull Object key) {
		log.info("toStoreValueWithKey called: key={}, value={}, valueType={}",
				key, value, value != null ? value.getClass().getSimpleName() : "null");
		if (value == null) {
			log.info("Value is null, calling super.toStoreValue(null)");
			return super.toStoreValue(null);
		}

		// 如果已经是 CacheMata，直接使用
		if (value instanceof CacheMata cacheMata) {
			log.info("Value is already CacheMata, storing directly");
			return super.toStoreValue(cacheMata);
		}

		// 包装为 CacheMata 对象
		CacheMata cacheMata = CacheMata.of(value);
		log.info("Wrapped value as CacheMata: {}", cacheMata);

		return super.toStoreValue(cacheMata);
	}

	/**
	 * 存储带 TTL 的缓存值
	 *
	 * @param key   缓存键
	 * @param value 缓存值
	 * @param ttl   生存时间（秒）
	 */
	public void put(@NonNull Object key, @Nullable Object value, long ttl) {
		log.debug("Putting cache value with TTL for key: {}, ttl: {}", key, ttl);
		if (value == null) {
			super.put(key, null);
			return;
		}

		CacheMata cacheMata;
		if (value instanceof CacheMata existingMata) {
			cacheMata = existingMata;
			// 更新 TTL
			cacheMata.setTtl(ttl);
		} else {
			cacheMata = CacheMata.of(value, ttl);
		}

		log.info("Wrapped value as CacheMata in put(ttl): wrappedType={}",
				cacheMata.getClass().getSimpleName());

		try {
			// 设置当前 key 到 ThreadLocal
			CURRENT_KEY.set(key);
			super.put(key, cacheMata);
		} finally {
			// 清理 ThreadLocal
			CURRENT_KEY.remove();
		}
	}

	/**
	 * 根据缓存键获取对应的缓存调用信息
	 *
	 * @param key 缓存键
	 * @return 缓存调用信息，如果未找到则返回null
	 */
	@Nullable
	private CachedInvocation getCachedInvocation(@NonNull Object key) {
		try {
			return invocationRegistry.get(getName(), key).orElse(null);
		} catch (Exception e) {
			log.warn("Failed to get cached invocation for cache: {}, key: {}, error: {}",
					getName(), key, e.getMessage());
			return null;
		}
	}

	/**
	 * 获取策略管理器（用于测试或扩展）
	 *
	 * @return 策略管理器
	 */
	@NonNull
	public CacheableStrategyManager getStrategyManager() {
		return strategyManager;
	}

	/**
	 * 获取调用注册表（用于测试或扩展）
	 *
	 * @return 调用注册表
	 */
	@NonNull
	public CacheInvocationRegistry getInvocationRegistry() {
		return invocationRegistry;
	}

	/**
	 * 公共访问的 toStoreValue 方法，用于测试
	 */
	public Object testToStoreValue(@Nullable Object value) {
		return this.toStoreValue(value);
	}

	/**
	 * 公共访问的 fromStoreValue 方法，用于测试
	 */
	public Object testFromStoreValue(@Nullable Object storeValue) {
		return this.fromStoreValue(storeValue);
	}

	/**
	 * 将值包装为 CacheMata 对象的辅助方法
	 *
	 * @param value 原始值
	 * @param key   缓存键
	 * @return 包装后的 CacheMata 对象
	 */
	private Object wrapValueAsCacheMata(@Nullable Object value, @NonNull Object key) {
		if (value == null) {
			return null;
		}

		// 如果已经是 CacheMata，直接使用
		if (value instanceof CacheMata) {
			return value;
		}

		// 尝试从缓存调用信息中获取TTL
		long ttl = -1; // 默认永不过期
		try {
			CachedInvocation cachedInvocation = getCachedInvocation(key);
			if (cachedInvocation != null && cachedInvocation.getCachedInvocationContext() != null) {
				ttl = cachedInvocation.getCachedInvocationContext().ttl();
				log.debug("Found TTL from CachedInvocation: {} for key: {}", ttl, key);
			}
		} catch (Exception e) {
			log.debug("Failed to get TTL from CachedInvocation for key: {}, using default", key);
		}

		// 包装为 CacheMata 对象
		return CacheMata.of(value, ttl);
	}

	/**
	 * 策略操作接口
	 */
	@FunctionalInterface
	private interface StrategyOperation<T> {
		T execute(CacheableStrategy<T> strategy, CachedInvocation cachedInvocation);
	}

	/**
	 * 父类操作接口
	 */
	@FunctionalInterface
	private interface ParentOperation<T> {
		T execute();
	}

	/**
	 * 支持 CacheMata 自动解包的 ValueWrapper 实现
	 */
	private record CacheMatAwareValueWrapper(ValueWrapper delegate) implements ValueWrapper {

		@Override
		public Object get() {
			Object value = delegate.get();
			// 如果是 CacheMata，自动解包到原始值
			if (value instanceof CacheMata cacheMata) {
				return cacheMata.getValue();
			}
			return value;
		}
	}
}
