package io.github.davidhlp.spring.cache.redis.register;

import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheEvictOperation;
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

        if (operation instanceof RedisCacheableOperation cacheableOp) {
            return cacheableOp;
        }

        // fallback: 尝试按 cacheName 前缀匹配
        RedisCacheableOperation fallback = findCacheableByCacheName(name, key);
        if (fallback != null) {
            log.debug("Fallback match found for cacheable operation: name={}, key={}", name, key);
            return fallback;
        }

        log.debug("Direct match failed for cacheable operation: name={}, key={}", name, key);
        return null;
    }

    /** 获取CacheEvict操作 */
    public RedisCacheEvictOperation getCacheEvictOperation(String name, String key) {
        // 先尝试直接匹配
        String operationKey = buildKey(name, key, "EVICT");
        CacheOperation operation = operationStrategy.get(operationKey);

        if (operation instanceof RedisCacheEvictOperation evictOp) {
            return evictOp;
        }

        // fallback: 尝试按 cacheName 前缀匹配
        RedisCacheEvictOperation fallback = findEvictByCacheName(name, key);
        if (fallback != null) {
            log.debug("Fallback match found for evict operation: name={}, key={}", name, key);
            return fallback;
        }

        log.debug("Direct match failed for evict operation: name={}, key={}", name, key);
        return null;
    }

    /**
     * fallback: 通过 cacheName 查找已注册的 CacheableOperation
     *
     * <p>注意：当前 EvictionStrategy 实现为 LRU 缓存结构，不支持遍历操作。
     * 完整的 fallback 实现需要额外的索引结构（如 ConcurrentHashMap）来支持按 cacheName 查找。
     * 此方法暂返回 null，待后续架构优化时实现。
     *
     * @param name 缓存名称
     * @param keyPrefix key前缀（暂未使用）
     * @return 始终返回 null（待实现）
     */
    private RedisCacheableOperation findCacheableByCacheName(String name, String keyPrefix) {
        // TODO: 实现 fallback 遍历查找
        // 需要在 EvictionStrategy 外层维护一个按 cacheName 的索引
        return null;
    }

    /**
     * fallback: 通过 cacheName 查找已注册的 CacheEvictOperation
     *
     * <p>同上，待后续架构优化时实现。
     */
    private RedisCacheEvictOperation findEvictByCacheName(String name, String keyPrefix) {
        // TODO: 实现 fallback 遍历查找
        return null;
    }

    /** 构建操作key */
    private String buildKey(String name, String key, String type) {
        return String.format("%s:%s:%s", type, name, key);
    }
}
