package com.david.spring.cache.redis.registry.interfaces;

import java.util.Optional;

/**
 * 调用信息注册器接口 - 接口分离原则（ISP）
 * 只包含注册相关的操作
 */
public interface InvocationRegistrar<T> {

	/**
	 * 注册调用信息
	 *
	 * @param cacheName  缓存名称
	 * @param key        缓存键
	 * @param invocation 调用信息
	 */
	void register(String cacheName, Object key, T invocation);

	/**
	 * 获取调用信息
	 *
	 * @param cacheName 缓存名称
	 * @param key       缓存键
	 * @return 调用信息的Optional包装
	 */
	Optional<T> get(String cacheName, Object key);
}