package com.david.spring.cache.redis.registry;

import com.david.spring.cache.redis.config.CacheGuardProperties;
import com.david.spring.cache.redis.registry.records.Key;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 抽象调用注册表基类
 * 提供通用的注册、获取、锁管理功能
 */
@Slf4j
public abstract class AbstractInvocationRegistry<T> {

	@Autowired
	private CacheGuardProperties properties;

	protected final ConcurrentMap<Key, T> invocations;
	protected final ConcurrentMap<Key, ReentrantLock> keyLocks;

	public AbstractInvocationRegistry() {
		// 使用默认配置初始化，实际配置会在Spring初始化后注入
		this.invocations = new ConcurrentHashMap<>(256, 0.75f, 16);
		this.keyLocks = new ConcurrentHashMap<>(256, 0.75f, 16);
	}

	/**
	 * 标准化键值（子类可以重写以处理特殊逻辑）
	 */
	protected Object normalizeKey(Object key) {
		return key;
	}

	/**
	 * 验证注册参数
	 */
	protected boolean isValidForRegistration(String cacheName, Object key, T invocation) {
		return cacheName == null || invocation == null;
	}

	/**
	 * 注册调用信息
	 */
	public void register(String cacheName, Object key, T invocation) {
		if (isValidForRegistration(cacheName, key, invocation)) {
			return;
		}
		Object normalizedKey = normalizeKey(key);
		invocations.put(new Key(cacheName, normalizedKey), invocation);
	}

	/**
	 * 获取调用信息
	 */
	public Optional<T> get(String cacheName, Object key) {
		if (cacheName == null) {
			return Optional.empty();
		}
		Object normalizedKey = normalizeKey(key);
		return Optional.ofNullable(invocations.get(new Key(cacheName, normalizedKey)));
	}

	/**
	 * 获取锁
	 */
	public ReentrantLock obtainLock(String cacheName, Object key) {
		Object normalizedKey = normalizeKey(key);
		return keyLocks.computeIfAbsent(new Key(cacheName, normalizedKey), k -> new ReentrantLock());
	}

	/**
	 * 移除单个条目
	 */
	public void remove(String cacheName, Object key) {
		Object normalizedKey = normalizeKey(key);
		Key k = new Key(cacheName, normalizedKey);
		invocations.remove(k);
		keyLocks.remove(k);
	}

	/**
	 * 按 cacheName 批量清理
	 */
	public void removeAll(String cacheName) {
		if (cacheName == null) return;
		invocations.keySet().removeIf(k -> cacheName.equals(k.cacheName()));
		keyLocks.keySet().removeIf(k -> cacheName.equals(k.cacheName()));
	}

	/**
	 * 获取当前注册的调用数量
	 */
	public int size() {
		return invocations.size();
	}

	/**
	 * 清空所有注册信息
	 */
	public void clear() {
		invocations.clear();
		keyLocks.clear();
	}
}