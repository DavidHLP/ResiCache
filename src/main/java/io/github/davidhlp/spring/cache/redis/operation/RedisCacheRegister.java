package io.github.davidhlp.spring.cache.redis.operation;

import io.github.davidhlp.spring.cache.redis.eviction.EvictionStrategy;
import io.github.davidhlp.spring.cache.redis.eviction.TwoListEvictionStrategy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.context.expression.AnnotatedElementKey;

import java.lang.reflect.Method;

/**
 * Redis 缓存注册器：以 {@link AnnotatedElementKey}（方法 + 目标类）为查找键，
 * 将三类缓存操作（Cacheable / Evict / Put）纳入统一淘汰策略管理，防止元数据内存膨胀。
 *
 * <p>三类操作的注册/查询逻辑同构（仅 type 标签 "CACHE"/"EVICT"/"PUT" 与返回类型不同），
 * 故收敛为 {@link #registerInternal} / {@link #getInternal} 两个私有泛型实现；
 * 六个公开具名方法作为薄包装保留——既是稳定 API，也作为
 * {@code AbstractAnnotationHandler} 方法引用的目标（{@code redisCacheRegister::registerCacheableOperation} 等），
 * 其第三参数为具体操作类型，规避泛型方法引用的类型推断陷阱。
 *
 * <p>查找键 = {@code <type>:<cacheName>:<elementKey.toString()>}，由 {@link #buildKey} 统一构造。
 * operation 自身的 {@code key} 字段（SpEL/字面量）是运行时缓存键的来源，与这里的注册查找键无关。
 */
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

    // ============================ 注册（薄包装）============================

    /** 注册 Cacheable 操作（基于 AnnotatedElementKey） */
    public void registerCacheableOperation(Method method, Class<?> targetClass,
                                           RedisCacheableOperation cacheOperation) {
        registerInternal(method, targetClass, cacheOperation, "CACHE");
    }

    /** 注册 CacheEvict 操作（基于 AnnotatedElementKey） */
    public void registerCacheEvictOperation(Method method, Class<?> targetClass,
                                            RedisCacheEvictOperation cacheOperation) {
        registerInternal(method, targetClass, cacheOperation, "EVICT");
    }

    /** 注册 CachePut 操作（基于 AnnotatedElementKey） */
    public void registerCachePutOperation(Method method, Class<?> targetClass,
                                          RedisCachePutOperation cacheOperation) {
        registerInternal(method, targetClass, cacheOperation, "PUT");
    }

    /**
     * 注册单个操作的内部实现：以方法+目标类构造 AnnotatedElementKey，按 cacheName 维度逐个写入淘汰策略。
     * 三类操作同构，统一在此处理。
     *
     * @param type 操作类型标签（"CACHE"/"EVICT"/"PUT"），用于区分查找键命名空间
     * @param <O> 操作类型
     */
    private <O extends CacheOperation> void registerInternal(
            Method method, Class<?> targetClass, O cacheOperation, String type) {
        AnnotatedElementKey elementKey = new AnnotatedElementKey(method, targetClass);
        for (String cacheName : cacheOperation.getCacheNames()) {
            String key = buildKey(cacheName, elementKey, type);
            operationStrategy.put(key, cacheOperation);
            log.debug(
                    "Registered {} operation: cacheName={}, elementKey={}, stats={}",
                    type, cacheName, elementKey, operationStrategy.getStats());
        }
    }

    // ============================ 查询（薄包装）============================

    /** 获取 Cacheable 操作（基于 AnnotatedElementKey） */
    public RedisCacheableOperation getCacheableOperation(String name, AnnotatedElementKey elementKey) {
        return getInternal(name, elementKey, "CACHE", RedisCacheableOperation.class);
    }

    /** 获取 CacheEvict 操作（基于 AnnotatedElementKey） */
    public RedisCacheEvictOperation getCacheEvictOperation(String name, AnnotatedElementKey elementKey) {
        return getInternal(name, elementKey, "EVICT", RedisCacheEvictOperation.class);
    }

    /** 获取 CachePut 操作（基于 AnnotatedElementKey） */
    public RedisCachePutOperation getCachePutOperation(String name, AnnotatedElementKey elementKey) {
        return getInternal(name, elementKey, "PUT", RedisCachePutOperation.class);
    }

    /**
     * 查询单个操作的内部实现：按 type 标签构造查找键，从淘汰策略取出并做类型断言；
     * 类型不匹配（不同操作复用同一 cacheName+elementKey）视为未命中，返回 null。
     *
     * @param operationType 期望的操作类型，用于 instance-of 断言与安全转型
     * @param <O> 操作类型
     */
    private <O extends CacheOperation> O getInternal(
            String name, AnnotatedElementKey elementKey, String type, Class<O> operationType) {
        String operationKey = buildKey(name, elementKey, type);
        CacheOperation operation = operationStrategy.get(operationKey);
        if (operationType.isInstance(operation)) {
            return operationType.cast(operation);
        }
        log.debug("{} operation not found: name={}, elementKey={}", type, name, elementKey);
        return null;
    }

    // ============================ 键构造 ============================

    /** 构建操作查找键：{@code <type>:<cacheName>:<elementKey>} */
    private String buildKey(String name, AnnotatedElementKey elementKey, String type) {
        String key = elementKey.toString();
        StringBuilder sb = new StringBuilder(type.length() + name.length() + key.length() + 2);
        sb.append(type).append(':').append(name).append(':').append(key);
        return sb.toString();
    }
}
