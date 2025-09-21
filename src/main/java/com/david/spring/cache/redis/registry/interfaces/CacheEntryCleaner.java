package com.david.spring.cache.redis.registry.interfaces;

/**
 * 缓存条目清理器接口 - 接口分离原则（ISP）
 * 只包含清理相关的操作
 */
public interface CacheEntryCleaner {

	/**
	 * 移除单个条目
	 *
	 * @param cacheName 缓存名称
	 * @param key       缓存键
	 */
	void remove(String cacheName, Object key);

	/**
	 * 按缓存名称批量清理
	 *
	 * @param cacheName 缓存名称
	 */
	void removeAll(String cacheName);

	/**
	 * 清理所有缓存条目
	 */
	void clearAll();
}