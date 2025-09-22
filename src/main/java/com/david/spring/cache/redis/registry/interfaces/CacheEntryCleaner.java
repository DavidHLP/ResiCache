package com.david.spring.cache.redis.registry.interfaces;

/**
 * 缓存清理接口 - 遵循接口分离原则
 */
public interface CacheEntryCleaner {

	/**
	 * 删除指定缓存中的单个条目
	 */
	void remove(String cacheName, Object key);

	/**
	 * 删除指定缓存中的所有条目
	 */
	void removeAll(String cacheName);

	/**
	 * 清空所有缓存
	 */
	void clearAll();
}