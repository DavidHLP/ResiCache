package com.david.spring.cache.redis.lock;

import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 锁工具类：提供各种锁操作的便利方法
 * 支持本地锁、分布式锁以及它们的组合使用
 */
@Slf4j
public final class LockUtils {

	private LockUtils() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	/**
	 * 先尝试获取本地锁，再尝试获取分布式锁，任意一步失败则不执行任务
	 *
	 * @return 是否成功执行了任务
	 */
	public static boolean runWithLocalTryThenDistTry(
			@Nonnull ReentrantLock localLock,
			@Nonnull DistributedLock distributedLock,
			@Nonnull String distKey,
			long distWaitTime,
			long distLeaseTime,
			@Nonnull TimeUnit unit,
			@Nonnull Runnable task) {
		validateParameters(localLock, distributedLock, distKey, unit, task);

		boolean localLocked = false;
		try {
			localLocked = tryAcquireLocalLock(localLock);
			if (!localLocked) {
				return false;
			}

			return executeWithDistributedLock(distributedLock, distKey, distWaitTime, distLeaseTime, unit, task);
		} catch (Exception e) {
			log.error("Error executing task with dual locks: {}", e.getMessage(), e);
			return false;
		} finally {
			releaseLocalLockIfHeld(localLock, localLocked);
		}
	}

	/**
	 * 验证输入参数
	 */
	private static void validateParameters(ReentrantLock localLock, DistributedLock distributedLock,
										  String distKey, TimeUnit unit, Runnable task) {
		Objects.requireNonNull(localLock, "localLock cannot be null");
		Objects.requireNonNull(distributedLock, "distributedLock cannot be null");
		Objects.requireNonNull(distKey, "distKey cannot be null");
		Objects.requireNonNull(unit, "unit cannot be null");
		Objects.requireNonNull(task, "task cannot be null");
	}

	/**
	 * 尝试获取本地锁
	 */
	private static boolean tryAcquireLocalLock(ReentrantLock localLock) {
		boolean localLocked = localLock.tryLock();
		if (!localLocked) {
			log.debug("Failed to acquire local lock");
		}
		return localLocked;
	}

	/**
	 * 使用分布式锁执行任务
	 */
	private static boolean executeWithDistributedLock(DistributedLock distributedLock, String distKey,
													  long distWaitTime, long distLeaseTime,
													  TimeUnit unit, Runnable task) {
		boolean distLocked = distributedLock.tryLock(distKey, distWaitTime, distLeaseTime, unit);
		if (!distLocked) {
			log.debug("Failed to acquire distributed lock: {}", distKey);
			return false;
		}

		try {
			task.run();
			log.debug("Successfully executed task with dual locks");
			return true;
		} finally {
			distributedLock.unlock(distKey);
		}
	}

	/**
	 * 如果持有本地锁则释放
	 */
	private static void releaseLocalLockIfHeld(ReentrantLock localLock, boolean localLocked) {
		if (localLocked) {
			localLock.unlock();
		}
	}

	/**
	 * 可抛异常版本的 Supplier
	 */
	@FunctionalInterface
	public interface ThrowingSupplier<T> {
		T get() throws Exception;
	}

}
