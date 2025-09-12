package com.david.spring.cache.redis.protection;

import com.david.spring.cache.redis.locks.DistributedLock;
import com.david.spring.cache.redis.locks.LockUtils;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 通用的缓存击穿（Cache Breakdown）防护工具：
 *
 * 目标：当缓存未命中时，通过“本地锁 + 分布式锁 + 双重检查”的方式，确保集群内只有一个请求回源，避免数据库被并发击穿。
 */
@Slf4j
public final class CacheBreakdown {

    private CacheBreakdown() {}

    /**
     * 在本地锁 + 分布式锁保护下进行加载（阻塞获取两把锁），并在供应器中完成双重检查与写回。
     *
     * 流程：
     * 1) 先尝试读取缓存（第一重检查），若命中直接返回。
     * 2) 在本地锁与分布式锁保护下，再次读取缓存（第二重检查），若命中直接返回。
     * 3) 调用 loader 回源，得到结果后通过 cacheWriter 写回缓存，并返回结果。
     *
     * 注意：若 loader 返回 null，默认抛出 NPE 以提醒调用方（与 Spring Cache 一致的防御策略）。
     */
    public static <T> T loadWithProtection(
            String name,
            String distKey,
            ReentrantLock localLock,
            DistributedLock distributedLock,
            long distLeaseTime,
            TimeUnit unit,
            Supplier<T> cacheReader,
            LockUtils.ThrowingSupplier<T> loader,
            Consumer<T> cacheWriter) throws Exception {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(distKey, "distKey");
        Objects.requireNonNull(localLock, "localLock");
        Objects.requireNonNull(distributedLock, "distributedLock");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(cacheReader, "cacheReader");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(cacheWriter, "cacheWriter");

        // 第一重检查：在加锁之前快速返回
        T first = cacheReader.get();
        if (first != null) {
            if (log.isDebugEnabled()) {
                log.debug("CB[{}] hit-before-lock, distKey={}", name, distKey);
            }
            return first;
        }

        if (log.isDebugEnabled()) {
            log.debug("CB[{}] attempt, distKey={}, leaseTime={} {}", name, distKey, distLeaseTime, unit);
        }

        return LockUtils.runWithLocalBlockThenDistBlock(
                localLock,
                distributedLock,
                distKey,
                distLeaseTime,
                unit,
                (LockUtils.ThrowingSupplier<T>) () -> {
                    // 第二重检查：已经持有两把锁
                    T second = cacheReader.get();
                    if (second != null) {
                        if (log.isDebugEnabled()) {
                            log.debug("CB[{}] hit-after-lock, distKey={}", name, distKey);
                        }
                        return second;
                    }

                    // 回源加载
                    T loaded = loader.get();
                    if (loaded == null) {
                        throw new NullPointerException("Loader returned null");
                    }
                    cacheWriter.accept(loaded);
                    if (log.isDebugEnabled()) {
                        log.debug("CB[{}] loaded-and-cached, distKey={}, valueType={}",
                                name, distKey, loaded.getClass().getSimpleName());
                    }
                    return loaded;
                });
    }
}
