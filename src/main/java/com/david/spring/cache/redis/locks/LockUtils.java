package com.david.spring.cache.redis.locks;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
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

        log.debug(
                "Attempting local lock followed by distributed lock for key: {}, distWaitTime={}, distLeaseTime={}, unit={}",
                distKey, distWaitTime, distLeaseTime, unit);
        boolean localLocked = false;
        try {
            localLocked = localLock.tryLock();
            if (!localLocked) {
                log.debug("Failed to acquire local lock for key: {}", distKey);
                return false;
            }
            log.debug("Local lock acquired for key: {}", distKey);

            boolean distLocked =
                    distributedLock.tryLock(distKey, distWaitTime, distLeaseTime, unit);
            if (!distLocked) {
                log.debug("Failed to acquire distributed lock for key: {}", distKey);
                return false;
            }
            log.debug(
                    "Distributed lock acquired for key: {}, leaseTime={}, unit={}",
                    distKey, distLeaseTime, unit);

            try {
                task.run();
                log.debug("Task executed successfully with locks for key: {}", distKey);
                return true;
            } finally {
                distributedLock.unlock(distKey);
                log.debug("Distributed lock released for key: {}", distKey);
            }
        } catch (Exception e) {
            log.error("Error during lock execution for key: {} - {}", distKey, e.getMessage(), e);
            return false;
        } finally {
            if (localLocked) {
                localLock.unlock();
                log.debug("Local lock released for key: {}", distKey);
            }
        }
    }

    /** 先阻塞获取本地锁，再阻塞获取分布式锁，均成功后执行可能抛出受检异常的 supplier。 */
    public static <T> T runWithLocalBlockThenDistBlock(
            ReentrantLock localLock,
            DistributedLock distributedLock,
            String distKey,
            long distLeaseTime,
            TimeUnit unit,
            ThrowingSupplier<T> supplier)
            throws Exception {
        Objects.requireNonNull(localLock, "localLock");
        Objects.requireNonNull(distributedLock, "distributedLock");
        Objects.requireNonNull(distKey, "distKey");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(supplier, "supplier");

        log.debug("Blocking for local lock then distributed lock for key: {}", distKey);
        localLock.lock();
        log.debug("Local lock acquired (blocking) for key: {}", distKey);
        try {
            distributedLock.lock(distKey, distLeaseTime, unit);
            log.debug("Distributed lock acquired (blocking) for key: {}", distKey);
            try {
                T result = supplier.get();
                log.debug("Supplier executed successfully with locks for key: {}", distKey);
                return result;
            } finally {
                distributedLock.unlock(distKey);
                log.debug("Distributed lock released (blocking mode) for key: {}", distKey);
            }
        } catch (Exception e) {
            log.error("Error during blocking lock execution for key: {} - {}", distKey, e.getMessage(), e);
            throw e;
        } finally {
            localLock.unlock();
            log.debug("Local lock released (blocking mode) for key: {}", distKey);
        }
    }

    /** 可抛异常版本的 Supplier。 */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
