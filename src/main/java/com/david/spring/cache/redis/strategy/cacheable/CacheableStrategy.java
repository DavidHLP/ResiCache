package com.david.spring.cache.redis.strategy.cacheable;

import com.david.spring.cache.redis.reflect.CachedInvocation;
import org.springframework.cache.Cache;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;

/**
 * 缓存策略接口，定义不同缓存场景下的处理策略
 * 支持基础缓存、保护机制、预刷新等策略
 *
 * @param <T> 缓存值类型
 * @author David
 */
public interface CacheableStrategy<T> {

	/**
	 * 获取缓存值
	 *
	 * @param cache            缓存实例
	 * @param key              缓存键
	 * @param cachedInvocation 缓存调用信息
	 * @return 缓存值包装器，如果未找到则返回null
	 */
	@Nullable
	Cache.ValueWrapper get(@NonNull Cache cache, @NonNull Object key, @NonNull CachedInvocation cachedInvocation);

	/**
	 * 获取缓存值，如果不存在则通过 valueLoader 加载
	 *
	 * @param cache            缓存实例
	 * @param key              缓存键
	 * @param valueLoader      值加载器
	 * @param cachedInvocation 缓存调用信息
	 * @return 缓存值，不会返回null
	 */
	@NonNull
	T get(@NonNull Cache cache, @NonNull Object key, @NonNull Callable<T> valueLoader, @NonNull CachedInvocation cachedInvocation);

	/**
	 * 存储缓存值
	 *
	 * @param cache            缓存实例
	 * @param key              缓存键
	 * @param value            缓存值
	 * @param cachedInvocation 缓存调用信息
	 */
	void put(@NonNull Cache cache, @NonNull Object key, @Nullable Object value, @NonNull CachedInvocation cachedInvocation);

	/**
	 * 如果不存在则存储缓存值
	 *
	 * @param cache            缓存实例
	 * @param key              缓存键
	 * @param value            缓存值
	 * @param cachedInvocation 缓存调用信息
	 * @return 存储后的值包装器
	 */
	@NonNull
	Cache.ValueWrapper putIfAbsent(@NonNull Cache cache, @NonNull Object key, @Nullable Object value, @NonNull CachedInvocation cachedInvocation);

	/**
	 * 驱逐缓存
	 *
	 * @param cache            缓存实例
	 * @param key              缓存键
	 * @param cachedInvocation 缓存调用信息
	 */
	void evict(@NonNull Cache cache, @NonNull Object key, @NonNull CachedInvocation cachedInvocation);

	/**
	 * 清空缓存
	 *
	 * @param cache            缓存实例
	 * @param cachedInvocation 缓存调用信息
	 */
	void clear(@NonNull Cache cache, @NonNull CachedInvocation cachedInvocation);

	/**
	 * 判断当前策略是否支持指定的缓存调用
	 *
	 * @param cachedInvocation 缓存调用信息
	 * @return 如果支持则返回true
	 */
	boolean supports(@NonNull CachedInvocation cachedInvocation);

	/**
	 * 获取策略名称
	 *
	 * @return 策略名称
	 */
	@NonNull
	String getStrategyName();

	/**
	 * 获取策略优先级（数值越小优先级越高）
	 *
	 * @return 优先级
	 */
	int getPriority();
}
