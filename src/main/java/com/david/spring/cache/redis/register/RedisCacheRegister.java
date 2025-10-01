package com.david.spring.cache.redis.register;

import com.david.spring.cache.redis.register.operation.RedisCacheEvictOperation;
import com.david.spring.cache.redis.register.operation.RedisCacheableOperation;
import com.david.spring.cache.redis.strategy.eviction.EvictionStrategy;
import com.david.spring.cache.redis.strategy.eviction.EvictionStrategyFactory;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.lang.NonNull;

/** Redis缓存注册器V2 使用通用淘汰策略管理缓存操作,防止内存占用过多 */
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
            log.info(
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
            log.info(
                    "Registered CacheEvict operation: cacheName={}, key={}, stats={}",
                    cacheName,
                    cacheOperation.getKey(),
                    operationStrategy.getStats());
        }
    }

    /** 获取Cacheable操作 */
    public RedisCacheableOperation getCacheableOperation(String name, String key) {
        // 先尝试直接匹配
        String operationKey = buildKey(name, key, "CACHE");
        CacheOperation operation = operationStrategy.get(operationKey);

        if (operation instanceof RedisCacheableOperation) {
            return (RedisCacheableOperation) operation;
        }

        // 如果直接匹配失败,遍历查找(性能较低,仅作为fallback)
        log.debug("Direct match failed for cacheable operation: name={}, key={}", name, key);
        return null;
    }

    /** 获取CacheEvict操作 */
    public RedisCacheEvictOperation getCacheEvictOperation(String name, String key) {
        // 先尝试直接匹配
        String operationKey = buildKey(name, key, "EVICT");
        CacheOperation operation = operationStrategy.get(operationKey);

        if (operation instanceof RedisCacheEvictOperation) {
            return (RedisCacheEvictOperation) operation;
        }

        // 如果直接匹配失败,遍历查找(性能较低,仅作为fallback)
        log.debug("Direct match failed for evict operation: name={}, key={}", name, key);
        return null;
    }

    /** 构建操作key */
    private String buildKey(String name, String key, String type) {
        return String.format("%s:%s:%s", type, name, key);
    }

    /** 获取Active List大小 */
    public int getActiveSize() {
        return operationStrategy.getStats().activeEntries();
    }

    /** 获取Inactive List大小 */
    public int getInactiveSize() {
        return operationStrategy.getStats().inactiveEntries();
    }

    /** 获取总操作数 */
    public int getTotalSize() {
        return operationStrategy.size();
    }

    /** 获取统计信息 */
    public String getStats() {
        return operationStrategy.getStats().toString();
    }
}

@Builder
record Key(String name, String key, OperationType operationType) {

    @Override
    @NonNull
    public String toString() {
        return "Key{"
                + "name='"
                + name
                + '\''
                + ", key='"
                + key
                + '\''
                + ", operationType="
                + operationType
                + '}';
    }

    public enum OperationType {
        EVICT,
        CACHE
    }
}
