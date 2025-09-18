package com.david.spring.cache.redis.strategy.cacheable.context;

import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.registry.impl.CacheInvocationRegistry;
import lombok.Builder;
import lombok.Data;
import org.springframework.lang.Nullable;

import java.util.concurrent.Executor;

/**
 * 执行上下文
 * 包含异步执行和方法调用相关的信息
 *
 * @author David
 */
@Data
@Builder
public class ExecutionContext {

	/** 异步执行器 */
	@Nullable
	private final Executor executor;

	/** 缓存调用注册中心 */
	@Nullable
	private final CacheInvocationRegistry registry;

	/** 缓存调用信息（懒加载） */
	@Nullable
	private CachedInvocation cachedInvocation;

	/**
	 * 获取缓存调用信息（懒加载）
	 *
	 * @param cacheName 缓存名称
	 * @param key       缓存键
	 * @return 缓存调用信息
	 */
	@Nullable
	public CachedInvocation getCachedInvocation(String cacheName, Object key) {
		if (cachedInvocation == null && registry != null) {
			cachedInvocation = registry.get(cacheName, key).orElse(null);
		}
		return cachedInvocation;
	}

	/**
	 * 检查是否支持异步执行
	 *
	 * @return 如果支持异步执行则返回true
	 */
	public boolean supportsAsyncExecution() {
		return executor != null;
	}

	/**
	 * 检查是否支持调用注册
	 *
	 * @return 如果支持调用注册则返回true
	 */
	public boolean supportsInvocationRegistry() {
		return registry != null;
	}

	/**
	 * 检查是否有调用信息
	 *
	 * @param cacheName 缓存名称
	 * @param key       缓存键
	 * @return 如果有调用信息则返回true
	 */
	public boolean hasInvocation(String cacheName, Object key) {
		return getCachedInvocation(cacheName, key) != null;
	}
}