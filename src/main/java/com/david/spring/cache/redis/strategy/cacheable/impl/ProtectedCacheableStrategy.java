package com.david.spring.cache.redis.strategy.cacheable.impl;

import com.david.spring.cache.redis.strategy.cacheable.CacheableStrategy;
import com.david.spring.cache.redis.strategy.cacheable.context.CacheGetContext;
import com.david.spring.cache.redis.strategy.cacheable.support.AvalancheProtector;
import com.david.spring.cache.redis.strategy.cacheable.support.BreakdownProtector;
import com.david.spring.cache.redis.strategy.cacheable.support.PenetrationProtector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

/**
 * 缓存保护策略
 * 集成缓存穿透、击穿、雪崩等保护机制
 *
 * @author David
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProtectedCacheableStrategy implements CacheableStrategy<Object> {

	private final PenetrationProtector penetrationProtector;
	private final BreakdownProtector breakdownProtector;
	private final AvalancheProtector avalancheProtector;

	@Override
	@Nullable
	public Cache.ValueWrapper get(@NonNull CacheGetContext<Object> context) {
		log.debug("Executing protected cache get strategy for key: {}", context.getKey());

		// 1. 缓存穿透保护
		if (!penetrationProtector.isAllowed(context)) {
			return null;
		}

		// 2. 尝试获取缓存
		Cache.ValueWrapper valueWrapper = context.getParentCache().get(context.getKey());

		if (valueWrapper == null) {
			log.debug("Cache miss with protection for key: {}", context.getKey());
			// 3. 缓存击穿保护
			return breakdownProtector.handleBreakdown(context);
		} else {
			log.debug("Cache hit with protection for key: {}", context.getKey());
			// 4. 缓存雪崩保护 (命中时)
			avalancheProtector.onCacheHit(context);
		}

		return valueWrapper;
	}

	@Override
	@Nullable
	public <V> V get(@NonNull CacheGetContext<Object> context, @NonNull Callable<V> valueLoader) {
		log.debug("Executing protected cache get with value loader for key: {}", context.getKey());

		// 1. 缓存穿透保护
		if (!penetrationProtector.isAllowed(context)) {
			return null;
		}

		// 2. 尝试从缓存获取
		Cache.ValueWrapper valueWrapper = context.getParentCache().get(context.getKey());
		if (valueWrapper != null) {
			log.debug("Cache hit with protection for key: {}", context.getKey());
			avalancheProtector.onCacheHit(context);
			@SuppressWarnings("unchecked")
			V value = (V) valueWrapper.get();
			return value;
		}

		// 3. 缓存未命中，使用击穿保护加载值
		log.debug("Cache miss with protection for key: {}. Applying breakdown protection.", context.getKey());
		return breakdownProtector.loadWithBreakdownProtection(context, valueLoader, this::loadAndCacheValue);
	}

	@Override
	public boolean supports(@NonNull CacheGetContext<Object> context) {
		// 根据细粒度配置判断是否需要保护
		return context.needsProtection();
	}

	@Override
	public int getOrder() {
		// 中等优先级，在预刷新之后，基础策略之前
		return 200;
	}

	/**
	 * 加载并缓存值
	 */
	@Nullable
	private <V> V loadAndCacheValue(CacheGetContext<Object> context, Callable<V> valueLoader) {
		try {
			V value = valueLoader.call();

			boolean shouldCacheNull = false;
			if (context.getCachedInvocationContext() != null) {
				shouldCacheNull = context.getCachedInvocationContext().cacheNullValues();
			}

			if (value != null || shouldCacheNull) {
				context.getParentCache().put(context.getKey(), value);
				log.debug("Value loaded and cached for key: {} (nullCached={})", context.getKey(), value == null);

				// 雪崩保护 - 记录缓存写入
				avalancheProtector.onCachePut(context);
			}

			return value;
		} catch (Exception e) {
			log.error("Error loading value for key: {}", context.getKey(), e);
			throw new Cache.ValueRetrievalException(context.getKey(), valueLoader, e);
		}
	}
}
