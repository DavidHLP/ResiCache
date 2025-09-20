package com.david.spring.cache.redis.protection;

import com.david.spring.cache.redis.lock.DistributedLock;
import com.david.spring.cache.redis.lock.LockUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 通用的缓存击穿（Cache Breakdown）防护工具：
 *
 * <p>
 * 目标：当缓存未命中时，通过“本地锁 + 分布式锁 + 双重检查”的方式，确保集群内只有一个请求回源，避免数据库被并发击穿。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class CacheBreakdown {

    private final DistributedLock distributedLock;

    /**
     * 在本地锁 +（必要时）分布式锁保护下进行加载，并在供应器中完成双重/三重检查与写回。
     *
     * <p>
     * 流程： 1) 先尝试读取缓存（第一重检查），若命中直接返回； 2)
     * 阻塞获取本地锁后，再次读取缓存（第二重检查），若命中直接返回（避免每个并发线程都去拿分布式锁）； 3)
     * 仅在确需回源时，阻塞获取分布式锁；在分布式锁内第三次检查，仍未命中才真正回源，写回并返回结果。
     *
     * <p>
     * 注意：若 loader 返回 null，默认抛出 NPE 以提醒调用方（与 Spring Cache 一致的防御策略）。
     */
    @NonNull
    public <T> T loadWithProtection(
            @NonNull String name,
            @NonNull String distKey,
            @NonNull ReentrantLock localLock,
            long distLeaseTime,
            @NonNull TimeUnit unit,
            @NonNull Supplier<T> cacheReader,
            @NonNull LockUtils.ThrowingSupplier<T> loader,
            @NonNull Consumer<T> cacheWriter)
            throws Exception {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(distKey, "distKey");
        Objects.requireNonNull(localLock, "localLock");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(cacheReader, "cacheReader");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(cacheWriter, "cacheWriter");

        // 第一重检查：在加锁之前快速返回
        T first = cacheReader.get();
        if (first != null) {
            log.debug("CacheBreakdown[{}] hit-before-lock, distKey={}", name, distKey);
            return first;
        }

        if (log.isDebugEnabled()) {
            log.debug(
                    "CacheBreakdown[{}] attempt, distKey={}, leaseTime={} {}",
                    name,
                    distKey,
                    distLeaseTime,
                    unit);
        }

        // 第二重检查：持有本地锁后快速返回，避免每个并发线程都争抢分布式锁
        localLock.lock();
        try {
            log.debug("CacheBreakdown[{}] local-lock acquired, distKey={}", name, distKey);
            T second = cacheReader.get();
            if (second != null) {
                log.debug("CacheBreakdown[{}] hit-after-local-lock, distKey={}", name, distKey);
                return second;
            }

            // 需要回源时，再去获取分布式锁

            log.debug(
                    "CacheBreakdown[{}] acquiring dist-lock, distKey={}, leaseTime={} {}",
                    name,
                    distKey,
                    distLeaseTime,
                    unit);
            this.distributedLock.lock(distKey, distLeaseTime, unit);
            try {

                log.debug("CacheBreakdown[{}] dist-lock acquired, distKey={}", name, distKey);
                // 第三重检查：持有分布式锁后再次确认
                T third = cacheReader.get();
                if (third != null) {
                    log.debug(
                            "CacheBreakdown[{}] hit-after-dist-lock, distKey={}",
                            name,
                            distKey);
                    return third;
                }

                // 回源加载
                long t0 = System.nanoTime();
                T loaded = loader.get();
                if (loaded == null) {
                    log.warn("CacheBreakdown[{}] loader returned null, distKey={}", name, distKey);
                    throw new NullPointerException("Loader returned null");
                }
                cacheWriter.accept(loaded);
                long loadMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                log.debug(
                        "CacheBreakdown[{}] loaded-and-cached, distKey={}, valueType={}, loadMs={}",
                        name,
                        distKey,
                        loaded.getClass().getSimpleName(),
                        loadMs);
                return loaded;
            } finally {
                this.distributedLock.unlock(distKey);
            }
        } finally {
            localLock.unlock();
        }
    }
}
