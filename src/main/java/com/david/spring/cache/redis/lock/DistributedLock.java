package com.david.spring.cache.redis.lock;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 基于 Redisson 的分布式锁封装。
 * 提供完整的锁操作API，支持函数式编程和异常处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLock {

	/** 锁 Key 的默认前缀，避免与业务普通 Key 冲突 */
	private static final String DEFAULT_LOCK_PREFIX = "lock:";

	/** 默认锁等待时间 */
	private static final long DEFAULT_WAIT_TIME = 3L;

	/** 默认锁租约时间 */
	private static final long DEFAULT_LEASE_TIME = 30L;

	private final RedissonClient redissonClient;

	/**
	 * 构建完整的锁键名
	 */
	private String buildKey(@Nonnull String rawKey) {
		if (rawKey.isBlank()) {
			throw new IllegalArgumentException("Lock key must not be blank");
		}
		return rawKey.startsWith(DEFAULT_LOCK_PREFIX) ? rawKey : DEFAULT_LOCK_PREFIX + rawKey;
	}

	/**
	 * 阻塞加锁，达到 leaseTime 到期后自动释放
	 */
	public void lock(@Nonnull String key, long leaseTime, @Nonnull TimeUnit unit) {
		RLock lock = redissonClient.getLock(buildKey(key));
		lock.lock(leaseTime, unit);
		log.debug("Acquired distributed lock: {}", buildKey(key));
	}

	/**
	 * 在 waitTime 内尝试加锁，成功后在 leaseTime 到期自动释放
	 */
	public boolean tryLock(@Nonnull String key, long waitTime, long leaseTime, @Nonnull TimeUnit unit) {
		RLock lock = redissonClient.getLock(buildKey(key));
		try {
			boolean acquired = lock.tryLock(waitTime, leaseTime, unit);
			if (acquired) {
				log.debug("Acquired distributed lock: {}", buildKey(key));
			} else {
				log.debug("Failed to acquire distributed lock: {}", buildKey(key));
			}
			return acquired;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Interrupted while trying to acquire lock: {}", buildKey(key));
			return false;
		}
	}

	/**
	 * 释放锁：仅当当前线程持有该锁时才执行解锁
	 */
	public void unlock(@Nonnull String key) {
		RLock lock = redissonClient.getLock(buildKey(key));
		try {
			if (lock.isHeldByCurrentThread()) {
				lock.unlock();
				log.debug("Released distributed lock: {}", buildKey(key));
			} else {
				log.debug("Lock not held by current thread, skip unlock: {}", buildKey(key));
			}
		} catch (IllegalMonitorStateException ex) {
			log.warn("Unlock skipped, current thread not holder: {}", buildKey(key));
		}
	}

	/**
	 * 尝试在锁保护下执行任务，失败时返回null
	 */
	@Nullable
	public <T> T tryWithLock(@Nonnull String key, @Nonnull Supplier<T> task) {
		return tryWithLock(key, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.SECONDS, task);
	}

	/**
	 * 尝试在锁保护下执行任务，支持自定义超时参数，失败时返回null
	 */
	@Nullable
	public <T> T tryWithLock(@Nonnull String key, long waitTime, long leaseTime,
	                         @Nonnull TimeUnit unit, @Nonnull Supplier<T> task) {
		if (tryLock(key, waitTime, leaseTime, unit)) {
			try {
				return task.get();
			} finally {
				unlock(key);
			}
		}
		return null;
	}

	/**
	 * 在锁保护下执行无返回值任务
	 */
	public void withLock(@Nonnull String key, @Nonnull Runnable task) {
		withLock(key, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.SECONDS, task);
	}

	/**
	 * 在锁保护下执行无返回值任务，支持自定义超时参数
	 */
	public void withLock(@Nonnull String key, long waitTime, long leaseTime,
	                     @Nonnull TimeUnit unit, @Nonnull Runnable task) {
		if (tryLock(key, waitTime, leaseTime, unit)) {
			try {
				task.run();
			} finally {
				unlock(key);
			}
		} else {
			throw new IllegalStateException("Failed to acquire lock: " + buildKey(key));
		}
	}

	/**
	 * 尝试在锁保护下执行无返回值任务
	 */
	public boolean tryWithLock(@Nonnull String key, @Nonnull Runnable task) {
		return tryWithLock(key, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.SECONDS, task);
	}

	/**
	 * 尝试在锁保护下执行无返回值任务，支持自定义超时参数
	 */
	public boolean tryWithLock(@Nonnull String key, long waitTime, long leaseTime,
	                           @Nonnull TimeUnit unit, @Nonnull Runnable task) {
		if (tryLock(key, waitTime, leaseTime, unit)) {
			try {
				task.run();
				return true;
			} finally {
				unlock(key);
			}
		}
		return false;
	}
}
