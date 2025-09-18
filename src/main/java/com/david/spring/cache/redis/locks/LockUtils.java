package com.david.spring.cache.redis.locks;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** 锁工具：封装“本地锁 + 分布式锁”的组合加锁模式。 */
public final class LockUtils {

	private LockUtils() {}

	/** 先尝试获取本地锁，再尝试获取分布式锁，任意一步失败则不执行任务。 返回是否成功执行了任务。 */
	public static boolean runWithLocalTryThenDistTry(
			ReentrantLock localLock,
			DistributedLock distributedLock,
			String distKey,
			long distWaitTime,
			long distLeaseTime,
			TimeUnit unit,
			Runnable task) {
		Objects.requireNonNull(localLock, "localLock");
		Objects.requireNonNull(distributedLock, "distributedLock");
		Objects.requireNonNull(distKey, "distKey");
		Objects.requireNonNull(unit, "unit");
		Objects.requireNonNull(task, "task");
		boolean localLocked = false;
		try {
			localLocked = localLock.tryLock();
			if (!localLocked) {
				return false;
			}
			boolean distLocked =
					distributedLock.tryLock(distKey, distWaitTime, distLeaseTime, unit);
			if (!distLocked) {
				return false;
			}
			try {
				task.run();
				return true;
			} finally {
				distributedLock.unlock(distKey);
			}
		} finally {
			if (localLocked) {
				localLock.unlock();
			}
		}
	}

	/** 可抛异常版本的 Supplier。 */
	@FunctionalInterface
	public interface ThrowingSupplier<T> {
		T get() throws Exception;
	}
}
