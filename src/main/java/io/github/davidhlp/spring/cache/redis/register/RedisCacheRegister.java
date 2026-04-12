package io.github.davidhlp.spring.cache.redis.register;

import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheEvictOperation;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCachePutOperation;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
import io.github.davidhlp.spring.cache.redis.strategy.eviction.EvictionStrategy;
import io.github.davidhlp.spring.cache.redis.strategy.eviction.EvictionStrategyFactory;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;

/** Redis缓存注册器 使用通用淘汰策略管理缓存操作,防止内存占用过多 */
@Slf4j
public class RedisCacheRegister {

    /** 缓存操作淘汰策略 */
    private final EvictionStrategy<String, CacheOperation> operationStrategy;

    public RedisCacheRegister() {
        this(2048, 1024);
    }

    public RedisCacheRegister(int maxActiveSize, int maxInactiveSize) {
        this.operationStrategy =
                EvictionStrategyFactory.createTwoList(maxActiveSize, maxInactiveSize);
    }

    /** 注册Cacheable操作 */
    public void registerCacheableOperation(RedisCacheableOperation cacheOperation) {
        for (String cacheName : cacheOperation.getCacheNames()) {
            String key = buildKey(cacheName, cacheOperation.getKey(), "CACHE");
            operationStrategy.put(key, cacheOperation);
            log.debug(
                    "Registered cacheable operation: cacheName={}, key={}, stats={}",
                    cacheName,
                    cacheOperation.getKey(),
                    operationStrategy.getStats());
        }
    }

    /** 注册CacheEvict操作 */
    public void registerCacheEvictOperation(RedisCacheEvictOperation cacheOperation) {
        for (String cacheName : cacheOperation.getCacheNames()) {
            String key = buildKey(cacheName, cacheOperation.getKey(), "EVICT");
            operationStrategy.put(key, cacheOperation);
            log.debug(
                    "Registered CacheEvict operation: cacheName={}, key={}, stats={}",
                    cacheName,
                    cacheOperation.getKey(),
                    operationStrategy.getStats());
        }
    }

    /** 注册CachePut操作 */
    public void registerCachePutOperation(RedisCachePutOperation cacheOperation) {
        for (String cacheName : cacheOperation.getCacheNames()) {
            String key = buildKey(cacheName, cacheOperation.getKey(), "PUT");
            operationStrategy.put(key, cacheOperation);
            log.debug(
                    "Registered CachePut operation: cacheName={}, key={}, stats={}",
                    cacheName,
                    cacheOperation.getKey(),
                    operationStrategy.getStats());
        }
    }

    /** 获取Cacheable操作 */
    public RedisCacheableOperation getCacheableOperation(String name, String key) {
        String operationKey = buildKey(name, key, "CACHE");
        CacheOperation operation = operationStrategy.get(operationKey);

        if (operation instanceof RedisCacheableOperation cacheableOp) {
            return cacheableOp;
        }

        log.debug("Cacheable operation not found: name={}, key={}", name, key);
        return null;
    }

    /** 获取CacheEvict操作 */
    public RedisCacheEvictOperation getCacheEvictOperation(String name, String key) {
        String operationKey = buildKey(name, key, "EVICT");
        CacheOperation operation = operationStrategy.get(operationKey);

        if (operation instanceof RedisCacheEvictOperation evictOp) {
            return evictOp;
        }

        log.debug("CacheEvict operation not found: name={}, key={}", name, key);
        return null;
    }

    /** 获取CachePut操作 */
    public RedisCachePutOperation getCachePutOperation(String name, String key) {
        String operationKey = buildKey(name, key, "PUT");
        CacheOperation operation = operationStrategy.get(operationKey);

        if (operation instanceof RedisCachePutOperation putOp) {
            return putOp;
        }

        log.debug("CachePut operation not found: name={}, key={}", name, key);
        return null;
    }

    /** 构建操作key */
    private String buildKey(String name, String key, String type) {
        return String.format("%s:%s:%s", type, name, key);
    }
}
