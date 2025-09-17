package com.david.spring.cache.redis.strategy.cacheable;

import com.david.spring.cache.redis.strategy.cacheable.context.CacheableContext;
import org.springframework.cache.Cache;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;

/**
 * 缓存获取策略接口
 * 定义不同的缓存获取策略，包括常规获取、预刷新、保护策略等
 *
 * @param <T> 缓存值类型
 * @author David
 */
public interface CacheableStrategy<T> {

	/**
	 * 执行缓存获取策略
	 *
	 * @param context 缓存获取上下文
	 * @return 缓存值包装器
	 */
	@Nullable
	Cache.ValueWrapper get(@NonNull CacheableContext<T> context);

	/**
	 * 带回退函数的缓存获取策略
	 *
	 * @param context     缓存获取上下文
	 * @param valueLoader 值加载器（回退函数）
	 * @return 缓存值
	 */
	@Nullable
	<V> V get(@NonNull CacheableContext<T> context, @NonNull Callable<V> valueLoader);

	/**
	 * 判断是否支持当前缓存操作类型
	 *
	 * @param context 缓存获取上下文
	 * @return 是否支持
	 */
	boolean supports(@NonNull CacheableContext<T> context);

	/**
	 * 获取策略优先级（数字越小优先级越高）
	 *
	 * @return 优先级
	 */
	default int getOrder() {
		return Integer.MAX_VALUE;
	}
}
