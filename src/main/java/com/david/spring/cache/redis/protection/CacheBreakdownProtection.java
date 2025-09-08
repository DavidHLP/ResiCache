package com.david.spring.cache.redis.protection;

import com.david.spring.cache.redis.locks.enums.LockType;
import com.david.spring.cache.redis.locks.interfaces.DistributedLockManager;
import com.david.spring.cache.redis.locks.interfaces.LockRetryStrategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 缓存击穿防护服务
 * 
 * <p>
 * 提供独立的缓存击穿防护功能，不依赖于特定的上下文，可被各种组件调用：
 * 1. 分布式锁防护：使用分布式锁确保只有一个线程重建缓存
 * 2. 双重检查：获得锁后再次检查缓存避免不必要的重建
 * 3. 重试机制：锁获取失败时的重试策略
 * 4. 降级策略：重试失败后的降级处理
 * 5. 异步更新：非阻塞的缓存更新机制
 *
 * @author david
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheBreakdownProtection {

    // 分布式锁配置
    public static final String LOCK_PREFIX = "cache:breakdown:lock:";
    public static final long DEFAULT_LOCK_WAIT_TIME = 3000; // 3秒等待时间
    public static final long DEFAULT_LOCK_LEASE_TIME = 10000; // 10秒持有时间
    public static final TimeUnit LOCK_TIME_UNIT = TimeUnit.MILLISECONDS;
    // 重试策略配置
    public static final int MAX_RETRIES = 3;
    public static final long MAX_WAIT_TIME = 5000; // 最大等待5秒
    public static final long DEFAULT_RETRY_BASE_DELAY = 100; // 重试基础延迟100ms
    private final DistributedLockManager lockManager;
    private final CacheManager cacheManager;

    /**
     * 执行带缓存击穿防护的操作
     *
     * @param cacheName     缓存名称
     * @param cacheKey      缓存键
     * @param dataLoader    数据加载器（当缓存未命中时调用）
     * @param <T>          数据类型
     * @return 防护结果
     */
    public <T> ProtectionResult<T> executeWithProtection(String cacheName, String cacheKey, 
                                                        Supplier<T> dataLoader) {
        return executeWithProtection(cacheName, cacheKey, dataLoader, 
                DEFAULT_LOCK_WAIT_TIME, DEFAULT_LOCK_LEASE_TIME, LOCK_TIME_UNIT);
    }

    /**
     * 执行带缓存击穿防护的操作（自定义锁配置）
     *
     * @param cacheName     缓存名称
     * @param cacheKey      缓存键
     * @param dataLoader    数据加载器
     * @param lockWaitTime  锁等待时间
     * @param lockLeaseTime 锁持有时间
     * @param timeUnit      时间单位
     * @param <T>          数据类型
     * @return 防护结果
     */
    public <T> ProtectionResult<T> executeWithProtection(String cacheName, String cacheKey, 
                                                        Supplier<T> dataLoader,
                                                        long lockWaitTime, long lockLeaseTime, 
                                                        TimeUnit timeUnit) {
        long startTime = System.currentTimeMillis();
        String lockKey = generateLockKey(cacheName, cacheKey);

        log.debug("开始缓存击穿防护: cacheName={}, key={}, lockKey={}", 
                cacheName, cacheKey, lockKey);

        // 1. 首先尝试直接读取缓存
        T cachedValue = getCachedValue(cacheName, cacheKey);
        if (cachedValue != null) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.debug("缓存命中，无需防护: cacheName={}, key={}, time={}ms", 
                    cacheName, cacheKey, executionTime);
            return new ProtectionResult<>(cachedValue, true, false, executionTime);
        }

        // 2. 缓存未命中，使用分布式锁防护
        try {
            return executeWithLock(cacheName, cacheKey, lockKey, dataLoader, 
                    lockWaitTime, lockLeaseTime, timeUnit, startTime);
        } catch (Exception e) {
            log.warn("分布式锁执行失败，尝试重试策略: cacheName={}, key={}, error={}", 
                    cacheName, cacheKey, e.getMessage());
            return executeWithRetry(cacheName, cacheKey, lockKey, dataLoader, startTime);
        }
    }

    /**
     * 使用分布式锁执行
     */
    private <T> ProtectionResult<T> executeWithLock(String cacheName, String cacheKey, String lockKey,
                                                   Supplier<T> dataLoader, long lockWaitTime, 
                                                   long lockLeaseTime, TimeUnit timeUnit, long startTime) {
        try {
            return lockManager.executeWithLock(
                    lockKey,
                    LockType.REENTRANT,
                    lockWaitTime,
                    lockLeaseTime,
                    timeUnit,
                    () -> {
                        // 获得锁后，再次检查缓存（双重检查）
                        T cachedValue = getCachedValue(cacheName, cacheKey);
                        if (cachedValue != null) {
                            long executionTime = System.currentTimeMillis() - startTime;
                            log.debug("双重检查缓存命中: cacheName={}, key={}, time={}ms", 
                                    cacheName, cacheKey, executionTime);
                            return new ProtectionResult<>(cachedValue, true, true, executionTime);
                        }

                        // 缓存仍然未命中，执行数据加载
                        log.debug("缓存确实失效，执行数据加载: cacheName={}, key={}", cacheName, cacheKey);
                        T result = dataLoader.get();

                        // 将结果写入缓存
                        if (result != null) {
                            updateCache(cacheName, cacheKey, result);
                        }

                        long executionTime = System.currentTimeMillis() - startTime;
                        log.debug("缓存重建完成: cacheName={}, key={}, time={}ms", 
                                cacheName, cacheKey, executionTime);
                        return new ProtectionResult<>(result, false, true, executionTime);
                    });
        } catch (Exception e) {
            throw new RuntimeException("分布式锁执行失败", e);
        }
    }

    /**
     * 使用重试策略执行
     */
    private <T> ProtectionResult<T> executeWithRetry(String cacheName, String cacheKey, String lockKey,
                                                    Supplier<T> dataLoader, long startTime) {
        LockRetryStrategy retryStrategy = new LockRetryStrategy.ExponentialBackoff();
        int retryCount = 0;

        while (true) {
            try {
                // 尝试获取锁
                boolean lockAcquired = lockManager.tryLock(
                        lockKey, LockType.REENTRANT, DEFAULT_LOCK_LEASE_TIME, LOCK_TIME_UNIT);

                if (lockAcquired) {
                    try {
                        // 执行缓存重建
                        T result = rebuildCache(cacheName, cacheKey, dataLoader);
                        long executionTime = System.currentTimeMillis() - startTime;
                        return new ProtectionResult<>(result, false, true, executionTime);
                    } finally {
                        lockManager.unlock(lockKey, LockType.REENTRANT);
                    }
                }

                // 检查是否应该重试
                long elapsedTime = System.currentTimeMillis() - startTime;
                if (!retryStrategy.shouldRetry(retryCount, MAX_RETRIES, elapsedTime, MAX_WAIT_TIME)) {
                    log.warn("缓存击穿防护重试达到上限，执行降级: cacheName={}, key={}, retryCount={}", 
                            cacheName, cacheKey, retryCount);
                    return executeFallback(cacheName, cacheKey, dataLoader, startTime);
                }

                // 计算延迟并等待
                long delay = retryStrategy.calculateDelay(retryCount, DEFAULT_RETRY_BASE_DELAY);
                log.debug("缓存击穿防护重试: cacheName={}, key={}, retryCount={}, delay={}ms", 
                        cacheName, cacheKey, retryCount, delay);

                Thread.sleep(delay);
                retryCount++;

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("缓存击穿防护被中断，执行降级: cacheName={}, key={}", cacheName, cacheKey);
                return executeFallback(cacheName, cacheKey, dataLoader, startTime);
            } catch (Exception e) {
                log.error("缓存击穿防护重试异常: cacheName={}, key={}, error={}", 
                        cacheName, cacheKey, e.getMessage());
                return executeFallback(cacheName, cacheKey, dataLoader, startTime);
            }
        }
    }

    /**
     * 重建缓存
     */
    private <T> T rebuildCache(String cacheName, String cacheKey, Supplier<T> dataLoader) {
        // 双重检查缓存
        T cachedValue = getCachedValue(cacheName, cacheKey);
        if (cachedValue != null) {
            log.debug("缓存重建时发现已存在: cacheName={}, key={}", cacheName, cacheKey);
            return cachedValue;
        }

        // 执行数据加载
        T result = dataLoader.get();

        // 写入缓存
        if (result != null) {
            updateCache(cacheName, cacheKey, result);
            log.debug("缓存重建完成: cacheName={}, key={}", cacheName, cacheKey);
        }

        return result;
    }

    /**
     * 降级执行
     */
    private <T> ProtectionResult<T> executeFallback(String cacheName, String cacheKey, 
                                                   Supplier<T> dataLoader, long startTime) {
        log.info("执行缓存击穿防护降级策略: cacheName={}, key={}", cacheName, cacheKey);

        T result = dataLoader.get();
        long executionTime = System.currentTimeMillis() - startTime;

        // 异步更新缓存（不阻塞主流程）
        if (result != null) {
            asyncUpdateCache(cacheName, cacheKey, result);
        }

        return new ProtectionResult<>(result, false, false, executionTime);
    }

    /**
     * 获取缓存值
     */
    @SuppressWarnings("unchecked")
    private <T> T getCachedValue(String cacheName, String cacheKey) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                log.warn("缓存 {} 不存在", cacheName);
                return null;
            }

            Cache.ValueWrapper valueWrapper = cache.get(cacheKey);
            return valueWrapper != null ? (T) valueWrapper.get() : null;
        } catch (Exception e) {
            log.warn("获取缓存值异常: cacheName={}, key={}, error={}", 
                    cacheName, cacheKey, e.getMessage());
            return null;
        }
    }

    /**
     * 更新缓存
     */
    public <T> boolean updateCache(String cacheName, String cacheKey, T value) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null && value != null) {
                cache.put(cacheKey, value);
                log.debug("缓存更新成功: cacheName={}, key={}", cacheName, cacheKey);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("缓存更新失败: cacheName={}, key={}, error={}", 
                    cacheName, cacheKey, e.getMessage());
            return false;
        }
    }

    /**
     * 异步更新缓存
     */
    public <T> void asyncUpdateCache(String cacheName, String cacheKey, T value) {
        CompletableFuture.runAsync(() -> {
            try {
                updateCache(cacheName, cacheKey, value);
                log.debug("异步缓存更新完成: cacheName={}, key={}", cacheName, cacheKey);
            } catch (Exception e) {
                log.warn("异步缓存更新失败: cacheName={}, key={}, error={}", 
                        cacheName, cacheKey, e.getMessage());
            }
        });
    }

    /**
     * 异步更新缓存（使用自定义执行器）
     */
    public <T> void asyncUpdateCache(String cacheName, String cacheKey, T value, Executor executor) {
        CompletableFuture.runAsync(() -> {
            try {
                updateCache(cacheName, cacheKey, value);
                log.debug("异步缓存更新完成: cacheName={}, key={}", cacheName, cacheKey);
            } catch (Exception e) {
                log.warn("异步缓存更新失败: cacheName={}, key={}, error={}", 
                        cacheName, cacheKey, e.getMessage());
            }
        }, executor);
    }

    /**
     * 生成锁键
     */
    public String generateLockKey(String cacheName, String cacheKey) {
        return LOCK_PREFIX + cacheName + ":" + cacheKey;
    }

    /**
     * 检查缓存是否存在
     */
    public boolean isCacheExists(String cacheName, String cacheKey) {
        return getCachedValue(cacheName, cacheKey) != null;
    }

    /**
     * 删除缓存
     */
    public boolean evictCache(String cacheName, String cacheKey) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.evict(cacheKey);
                log.debug("缓存删除成功: cacheName={}, key={}", cacheName, cacheKey);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("缓存删除失败: cacheName={}, key={}, error={}", 
                    cacheName, cacheKey, e.getMessage());
            return false;
        }
    }

    /**
     * 清空整个缓存
     */
    public boolean clearCache(String cacheName) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
                log.debug("缓存清空成功: cacheName={}", cacheName);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("缓存清空失败: cacheName={}, error={}", cacheName, e.getMessage());
            return false;
        }
    }

    /**
     * 获取锁管理器（供高级用法）
     */
    public DistributedLockManager getLockManager() {
        return lockManager;
    }

    /**
     * 获取缓存管理器（供高级用法）
     */
    public CacheManager getCacheManager() {
        return cacheManager;
    }

	/**
	     * 缓存击穿防护结果
	     */
	    public record ProtectionResult<T>(T value,boolean cacheHit,boolean lockAcquired,long executionTimeMs) {
	}
}
