package com.david.spring.cache.redis.strategy.cacheable.impl;

import com.david.spring.cache.redis.strategy.cacheable.CacheableStrategy;
import com.david.spring.cache.redis.strategy.cacheable.context.CacheableContext;
import com.david.spring.cache.redis.strategy.cacheable.support.CacheProtectionManager;
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

	private final CacheProtectionManager protectionManager;

	@Override
	@Nullable
	public Cache.ValueWrapper get(@NonNull CacheableContext<Object> context) {
		log.debug("Executing protected cache get strategy for key: {}", context.getKey());
		return protectionManager.getWithProtection(context);
	}

	@Override
	@Nullable
	public <V> V get(@NonNull CacheableContext<Object> context, @NonNull Callable<V> valueLoader) {
		log.debug("Executing protected cache get with value loader for key: {}", context.getKey());
		return protectionManager.getWithProtection(context, valueLoader);
	}

	@Override
	public boolean supports(@NonNull CacheableContext<Object> context) {
		// 根据细粒度配置判断是否需要保护
		return context.needsProtection();
	}

	@Override
	public int getOrder() {
		// 中等优先级，在预刷新之后，基础策略之前
		return 200;
	}
}
