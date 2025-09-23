package com.david.spring.cache.redis.cache.support;

import com.david.spring.cache.redis.config.CacheGuardProperties;
import com.david.spring.cache.redis.lock.DistributedLock;
import com.david.spring.cache.redis.lock.LockUtils;
import com.david.spring.cache.redis.registry.RegistryFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class CacheAsyncOperationService {

    private final CacheGuardProperties properties;
    private final Executor executor;
    private final DistributedLock distributedLock;
    private final RegistryFactory registryFactory;
    private final CacheRegistryService registryService;

    public CacheAsyncOperationService(CacheGuardProperties properties,
                                      Executor executor,
                                      DistributedLock distributedLock,
                                      RegistryFactory registryFactory,
                                      CacheRegistryService registryService) {
        this.properties = properties;
        this.executor = executor;
        this.distributedLock = distributedLock;
        this.registryFactory = registryFactory;
        this.registryService = registryService;
    }

    public void doEvictInternal(String cacheName, Object key, RedisCacheWriter cacheWriter, byte[] serializedKey) {
        try {
            cacheWriter.remove(cacheName, serializedKey);
        } finally {
            registryService.cleanupRegistries(cacheName, key);
        }
    }

    public void scheduleSecondDeleteForKey(String cacheName, Object key, RedisCacheWriter cacheWriter,
                                           byte[] serializedKey, String cacheKey) {
        Executor delayed = CompletableFuture.delayedExecutor(
                properties.getDoubleDeleteDelayMs(), TimeUnit.MILLISECONDS, executor);
        CompletableFuture.runAsync(
                () -> executeWithLock(cacheName, key, cacheKey,
                    () -> doEvictInternal(cacheName, key, cacheWriter, serializedKey)),
                delayed);
    }

    public void doClearInternal(String cacheName, Runnable clearOperation) {
        try {
            clearOperation.run();
        } finally {
            registryService.cleanupAllRegistries(cacheName);
        }
    }

    public void scheduleSecondClear(String cacheName, String cacheKey, Runnable clearOperation) {
        Executor delayed = CompletableFuture.delayedExecutor(
                properties.getDoubleDeleteDelayMs(), TimeUnit.MILLISECONDS, executor);
        CompletableFuture.runAsync(
                () -> executeWithLock(cacheName, "*", cacheKey, () -> doClearInternal(cacheName, clearOperation)),
                delayed);
    }

    private void executeWithLock(String cacheName, Object key, String cacheKey, Runnable operation) {
        String distKey = "cache:evict:" + cacheKey;
        ReentrantLock localLock = registryFactory.getEvictInvocationRegistry().obtainLock(cacheName, key);
        LockUtils.runWithLocalTryThenDistTry(
                localLock,
                distributedLock,
                distKey,
                0L,
                5L,
                TimeUnit.SECONDS,
                operation);
    }
}