package com.david.spring.cache.redis.registry.interfaces;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 锁提供者接口 - 接口分离原则（ISP）
 * 只包含锁相关的操作
 */
public interface LockProvider {

	/**
	 * 获取锁
	 *
	 * @param cacheName 缓存名称
	 * @param key       缓存键
	 * @return 可重入锁实例
	 */
	ReentrantLock obtainLock(String cacheName, Object key);
}