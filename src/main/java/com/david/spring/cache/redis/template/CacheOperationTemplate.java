package com.david.spring.cache.redis.template;

import org.springframework.cache.Cache;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * 缓存操作模板抽象类
 * 使用模板方法模式定义缓存操作的标准流程
 */
public abstract class CacheOperationTemplate {


	/**
	 * 查找缓存值的模板方法
	 */
	public final Object lookup(String cacheName, Object key) {
		try {
			beforeLookup(cacheName, key);
			Object rawValue = doLookup(cacheName, key);
			return afterLookup(cacheName, key, rawValue);
		} catch (Exception e) {
			handleLookupError(cacheName, key, e);
			return null;
		}
	}

	/**
	 * 存储缓存值的模板方法
	 */
	public final void put(String cacheName, Object key, Object value, Duration ttl) {
		try {
			beforePut(cacheName, key, value, ttl);
			doPut(cacheName, key, value, ttl);
			afterPut(cacheName, key, value, ttl);
		} catch (Exception e) {
			handlePutError(cacheName, key, value, e);
		}
	}

	/**
	 * 条件存储缓存值的模板方法
	 */
	public final Cache.ValueWrapper putIfAbsent(String cacheName, Object key, Object value, Duration ttl) {
		try {
			beforePutIfAbsent(cacheName, key, value, ttl);
			Cache.ValueWrapper result = doPutIfAbsent(cacheName, key, value, ttl);
			afterPutIfAbsent(cacheName, key, value, ttl, result);
			return result;
		} catch (Exception e) {
			handlePutIfAbsentError(cacheName, key, value, e);
			return null;
		}
	}

	/**
	 * 删除缓存项的模板方法
	 */
	public final void evict(String cacheName, Object key) {
		try {
			beforeEvict(cacheName, key);
			doEvict(cacheName, key);
			afterEvict(cacheName, key);
		} catch (Exception e) {
			handleEvictError(cacheName, key, e);
		}
	}

	/**
	 * 清空缓存的模板方法
	 */
	public final void clear(String cacheName) {
		try {
			beforeClear(cacheName);
			doClear(cacheName);
			afterClear(cacheName);
		} catch (Exception e) {
			handleClearError(cacheName, e);
		}
	}

	/**
	 * 获取或加载缓存值的模板方法
	 */
	public final <T> T get(String cacheName, Object key, Callable<T> valueLoader) {
		try {
			beforeGet(cacheName, key, valueLoader);
			T result = doGet(cacheName, key, valueLoader);
			afterGet(cacheName, key, valueLoader, result);
			return result;
		} catch (Exception e) {
			handleGetError(cacheName, key, valueLoader, e);
			throw new RuntimeException(e);
		}
	}


	// 核心缓存操作的抽象方法
	protected abstract Object doLookup(String cacheName, Object key);

	protected abstract void doPut(String cacheName, Object key, Object value, Duration ttl);

	protected abstract Cache.ValueWrapper doPutIfAbsent(String cacheName, Object key, Object value, Duration ttl);

	protected abstract void doEvict(String cacheName, Object key);

	protected abstract void doClear(String cacheName);

	protected abstract <T> T doGet(String cacheName, Object key, Callable<T> valueLoader) throws Exception;

	// 钩子方法（可选重写）
	protected void beforeLookup(String cacheName, Object key) {}

	protected Object afterLookup(String cacheName, Object key, Object rawValue) {return rawValue;}

	protected void handleLookupError(String cacheName, Object key, Exception e) {}

	protected void beforePut(String cacheName, Object key, Object value, Duration ttl) {}

	protected void afterPut(String cacheName, Object key, Object value, Duration ttl) {}

	protected void handlePutError(String cacheName, Object key, Object value, Exception e) {}

	protected void beforePutIfAbsent(String cacheName, Object key, Object value, Duration ttl) {}

	protected void afterPutIfAbsent(String cacheName, Object key, Object value, Duration ttl, Cache.ValueWrapper result) {}

	protected void handlePutIfAbsentError(String cacheName, Object key, Object value, Exception e) {}

	protected void beforeEvict(String cacheName, Object key) {}

	protected void afterEvict(String cacheName, Object key) {}

	protected void handleEvictError(String cacheName, Object key, Exception e) {}

	protected void beforeClear(String cacheName) {}

	protected void afterClear(String cacheName) {}

	protected void handleClearError(String cacheName, Exception e) {}

	protected void beforeGet(String cacheName, Object key, Callable<?> valueLoader) {}

	protected void afterGet(String cacheName, Object key, Callable<?> valueLoader, Object result) {}

	protected void handleGetError(String cacheName, Object key, Callable<?> valueLoader, Exception e) {}

}