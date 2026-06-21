package io.github.davidhlp.spring.cache.redis.operation;

import io.github.davidhlp.spring.cache.redis.eviction.EvictionStrategy;
import io.github.davidhlp.spring.cache.redis.eviction.TwoListEvictionStrategy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.context.expression.AnnotatedElementKey;

import java.lang.reflect.Method;

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
                new TwoListEvictionStrategy<>(maxActiveSize, maxInactiveSize);
    }

    /** 注册Cacheable操作（基于 AnnotatedElementKey，推荐方式） */
    public void registerCacheableOperation(Method method, Class<?> targetClass, RedisCacheableOperation cacheOperation) {
        AnnotatedElementKey elementKey = new AnnotatedElementKey(method, targetClass);
        for (String cacheName : cacheOperation.getCacheNames()) {
            String key = buildKey(cacheName, elementKey, "CACHE");
            operationStrategy.put(key, cacheOperation);
            log.debug(
                    "Registered cacheable operation: cacheName={}, elementKey={}, stats={}",
                    cacheName,
                    elementKey,
                    operationStrategy.getStats());
        }
    }

    /** 注册Cacheable操作（向后兼容，基于 key 字符串） */
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

    /** 注册CacheEvict操作（基于 AnnotatedElementKey，推荐方式） */
    public void registerCacheEvictOperation(Method method, Class<?> targetClass, RedisCacheEvictOperation cacheOperation) {
        AnnotatedElementKey elementKey = new AnnotatedElementKey(method, targetClass);
        for (String cacheName : cacheOperation.getCacheNames()) {
            String key = buildKey(cacheName, elementKey, "EVICT");
            operationStrategy.put(key, cacheOperation);
            log.debug(
                    "Registered CacheEvict operation: cacheName={}, elementKey={}, stats={}",
                    cacheName,
                    elementKey,
                    operationStrategy.getStats());
        }
    }

    /** 注册CacheEvict操作（向后兼容，基于 key 字符串） */
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

    /** 注册CachePut操作（基于 AnnotatedElementKey，推荐方式） */
    public void registerCachePutOperation(Method method, Class<?> targetClass, RedisCachePutOperation cacheOperation) {
        AnnotatedElementKey elementKey = new AnnotatedElementKey(method, targetClass);
        for (String cacheName : cacheOperation.getCacheNames()) {
            String key = buildKey(cacheName, elementKey, "PUT");
            operationStrategy.put(key, cacheOperation);
            log.debug(
                    "Registered CachePut operation: cacheName={}, elementKey={}, stats={}",
                    cacheName,
                    elementKey,
                    operationStrategy.getStats());
        }
    }

    /** 注册CachePut操作（向后兼容，基于 key 字符串） */
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

    /** 获取Cacheable操作（基于 AnnotatedElementKey，推荐方式） */
    public RedisCacheableOperation getCacheableOperation(String name, AnnotatedElementKey elementKey) {
        String operationKey = buildKey(name, elementKey, "CACHE");
        CacheOperation operation = operationStrategy.get(operationKey);

        if (operation instanceof RedisCacheableOperation cacheableOp) {
            return cacheableOp;
        }

        log.debug("Cacheable operation not found: name={}, elementKey={}", name, elementKey);
        return null;
    }

    /** 获取Cacheable操作（向后兼容，基于 key 字符串） */
    public RedisCacheableOperation getCacheableOperation(String name, String key) {
        String operationKey = buildKey(name, key, "CACHE");
        CacheOperation operation = operationStrategy.get(operationKey);

        if (operation instanceof RedisCacheableOperation cacheableOp) {
            return cacheableOp;
        }

        log.debug("Cacheable operation not found: name={}, key={}", name, key);
        return null;
    }

    /** 获取CacheEvict操作（基于 AnnotatedElementKey，推荐方式） */
    public RedisCacheEvictOperation getCacheEvictOperation(String name, AnnotatedElementKey elementKey) {
        String operationKey = buildKey(name, elementKey, "EVICT");
        CacheOperation operation = operationStrategy.get(operationKey);

        if (operation instanceof RedisCacheEvictOperation evictOp) {
            return evictOp;
        }

        log.debug("CacheEvict operation not found: name={}, elementKey={}", name, elementKey);
        return null;
    }

    /** 获取CacheEvict操作（向后兼容，基于 key 字符串） */
    public RedisCacheEvictOperation getCacheEvictOperation(String name, String key) {
        String operationKey = buildKey(name, key, "EVICT");
        CacheOperation operation = operationStrategy.get(operationKey);

        if (operation instanceof RedisCacheEvictOperation evictOp) {
            return evictOp;
        }

        log.debug("CacheEvict operation not found: name={}, key={}", name, key);
        return null;
    }

    /** 获取CachePut操作（基于 AnnotatedElementKey，推荐方式） */
    public RedisCachePutOperation getCachePutOperation(String name, AnnotatedElementKey elementKey) {
        String operationKey = buildKey(name, elementKey, "PUT");
        CacheOperation operation = operationStrategy.get(operationKey);

        if (operation instanceof RedisCachePutOperation putOp) {
            return putOp;
        }

        log.debug("CachePut operation not found: name={}, elementKey={}", name, elementKey);
        return null;
    }

    /** 获取CachePut操作（向后兼容，基于 key 字符串） */
    public RedisCachePutOperation getCachePutOperation(String name, String key) {
        String operationKey = buildKey(name, key, "PUT");
        CacheOperation operation = operationStrategy.get(operationKey);

        if (operation instanceof RedisCachePutOperation putOp) {
            return putOp;
        }

        log.debug("CachePut operation not found: name={}, key={}", name, key);
        return null;
    }

    /** 构建操作key（基于 AnnotatedElementKey） */
    private String buildKey(String name, AnnotatedElementKey elementKey, String type) {
        String key = elementKey.toString();
        StringBuilder sb = new StringBuilder(type.length() + name.length() + key.length() + 2);
        sb.append(type).append(':').append(name).append(':').append(key);
        return sb.toString();
    }

    /** 构建操作key（基于 key 字符串，向后兼容） */
    private String buildKey(String name, String key, String type) {
        StringBuilder sb = new StringBuilder(type.length() + name.length() + key.length() + 2);
        sb.append(type).append(':').append(name).append(':').append(key);
        return sb.toString();
    }
}
