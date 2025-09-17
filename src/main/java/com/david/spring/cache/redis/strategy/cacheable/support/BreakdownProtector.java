package com.david.spring.cache.redis.strategy.cacheable.support;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.strategy.cacheable.context.CacheGetContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 缓存击穿保护器
 *
 * @author David
 */
@Slf4j
@Component
public class BreakdownProtector {

	/**
	 * 处理缓存击穿保护（无值加载器）
	 *
	 * @param context 缓存获取上下文
	 * @return 缓存值包装器
	 */
	@Nullable
	public Cache.ValueWrapper handleBreakdown(CacheGetContext<Object> context) {
		if (!needsBreakdownProtection(context)) {
			return null;
		}

		CachedInvocationContext cic = context.getCachedInvocationContext();
		boolean useDist = cic != null && cic.distributedLock() && context.getDistributedLock() != null;
		boolean useLocal = !useDist && cic != null && cic.internalLock() && context.getRegistry() != null;

		if (useDist) {
			String lockKey = resolveDistLockKey(context);
			try {
				if (context.getDistributedLock().tryLock(lockKey, 5000, 30000, TimeUnit.MILLISECONDS)) {
					try {
						// 获取锁后再次检查缓存
						Cache.ValueWrapper valueWrapper = context.getParentCache().get(context.getKey());
						if (valueWrapper != null) {
							log.debug("Cache hit after acquiring breakdown protection lock for key: {}", context.getKey());
							return valueWrapper;
						}

						log.debug("Cache breakdown protection applied for key: {}. No value loader, returning null.", context.getKey());
						return null;

					} finally {
						context.getDistributedLock().unlock(lockKey);
					}
				} else {
					log.debug("Failed to acquire distributed breakdown lock for key: {}", context.getKey());
					return null;
				}
			} catch (Exception e) {
				log.error("Error during distributed breakdown protection for key: {}", context.getKey(), e);
				return null;
			}
		} else if (useLocal) {
			ReentrantLock lock = context.getRegistry().obtainLock(context.getCacheName(), context.getKey());
			boolean locked = false;
			try {
				locked = lock.tryLock(5, TimeUnit.SECONDS);
				if (!locked) {
					log.debug("Failed to acquire local breakdown lock for key: {}", context.getKey());
					return null;
				}
				// 获取锁后再次检查缓存
				Cache.ValueWrapper valueWrapper = context.getParentCache().get(context.getKey());
				if (valueWrapper != null) {
					log.debug("Cache hit after acquiring local breakdown lock for key: {}", context.getKey());
					return valueWrapper;
				}
				log.debug("Local breakdown protection applied for key: {}. No value loader, returning null.", context.getKey());
				return null;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			} catch (Exception e) {
				log.error("Error during local breakdown protection for key: {}", context.getKey(), e);
				return null;
			} finally {
				if (locked) {
					try {
						lock.unlock();
					} catch (Exception ignore) {
					}
				}
			}
		}
		return null;
	}

	/**
	 * 带击穿保护的值加载
	 *
	 * @param context     缓存上下文
	 * @param valueLoader 值加载器
	 * @param loader      实际的值加载和缓存函数
	 * @return 加载的值
	 */
	@Nullable
	public <V> V loadWithBreakdownProtection(CacheGetContext<Object> context, Callable<V> valueLoader, ValueLoader<V> loader) {
		if (!needsBreakdownProtection(context)) {
			return loader.load(context, valueLoader);
		}

		CachedInvocationContext cic = context.getCachedInvocationContext();
		boolean useDist = cic != null && cic.distributedLock() && context.getDistributedLock() != null;
		boolean useLocal = !useDist && cic != null && cic.internalLock() && context.getRegistry() != null;

		if (useDist) {
			String lockKey = resolveDistLockKey(context);
			try {
				if (context.getDistributedLock().tryLock(lockKey, 5000, 30000, TimeUnit.MILLISECONDS)) {
					try {
						// 获取锁后再次检查缓存
						Cache.ValueWrapper valueWrapper = context.getParentCache().get(context.getKey());
						if (valueWrapper != null) {
							@SuppressWarnings("unchecked")
							V value = (V) valueWrapper.get();
							log.debug("Cache hit after acquiring breakdown protection lock for key: {}", context.getKey());
							return value;
						}

						// 缓存仍然未命中，加载值
						return loader.load(context, valueLoader);

					} finally {
						context.getDistributedLock().unlock(lockKey);
					}
				} else {
					log.debug("Failed to acquire distributed breakdown lock for key: {}, returning null", context.getKey());
					return null;
				}
			} catch (Exception e) {
				log.error("Error during protected value loading (dist lock) for key: {}", context.getKey(), e);
				throw new Cache.ValueRetrievalException(context.getKey(), valueLoader, e);
			}
		} else if (useLocal) {
			ReentrantLock lock = context.getRegistry().obtainLock(context.getCacheName(), context.getKey());
			boolean locked = false;
			try {
				locked = lock.tryLock(5, TimeUnit.SECONDS);
				if (!locked) {
					log.debug("Failed to acquire local breakdown lock for key: {}, returning null", context.getKey());
					return null;
				}
				// 获取锁后再次检查缓存
				Cache.ValueWrapper valueWrapper = context.getParentCache().get(context.getKey());
				if (valueWrapper != null) {
					@SuppressWarnings("unchecked")
					V value = (V) valueWrapper.get();
					log.debug("Cache hit after acquiring local breakdown lock for key: {}", context.getKey());
					return value;
				}
				// 缓存仍然未命中，加载值
				return loader.load(context, valueLoader);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			} catch (Exception e) {
				log.error("Error during protected value loading (local lock) for key: {}", context.getKey(), e);
				throw new Cache.ValueRetrievalException(context.getKey(), valueLoader, e);
			} finally {
				if (locked) {
					try {
						lock.unlock();
					} catch (Exception ignore) {
					}
				}
			}
		}
		return null;
	}

	private boolean needsBreakdownProtection(CacheGetContext<Object> context) {
		CachedInvocationContext cic = context.getCachedInvocationContext();
		if (cic == null || context.getCacheBreakdown() == null) {
			return false;
		}
		boolean canDist = cic.distributedLock() && context.getDistributedLock() != null;
		boolean canLocal = cic.internalLock() && context.getRegistry() != null;
		return canDist || canLocal;
	}

	private String resolveDistLockKey(CacheGetContext<Object> context) {
		CachedInvocationContext cic = context.getCachedInvocationContext();
		String prefix = (cic != null && cic.distributedLockName() != null && !cic.distributedLockName().isBlank())
				? cic.distributedLockName()
				: "breakdown";
		return prefix + ":" + context.createCacheKey();
	}

	/**
	 * 用于传递值加载逻辑的函数式接口
	 *
	 * @param <V> 值的类型
	 */
	@FunctionalInterface
	public interface ValueLoader<V> {
		@Nullable
		V load(CacheGetContext<Object> context, Callable<V> valueLoader);
	}
}
