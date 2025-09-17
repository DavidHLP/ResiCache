package com.david.spring.cache.redis.registry;

import com.david.spring.cache.redis.registry.keys.Key;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 抽象调用注册表基类，提供通用的注册、查询、锁管理功能
 * 主人，这个抽象类让代码更加优雅和可维护了喵~
 * 
 * @param <T> 调用类型（CachedInvocation 或 EvictInvocation）
 */
@Slf4j
public abstract class AbstractInvocationRegistry<T> {

    private final ConcurrentMap<Key, T> invocations = new ConcurrentHashMap<>();
    private final ConcurrentMap<Key, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    /**
     * 注册调用映射
     * 
     * @param cacheName  缓存名称
     * @param key        缓存键
     * @param invocation 调用实例
     */
    public void register(String cacheName, @Nullable Object key, T invocation) {
        if (cacheName == null || invocation == null) {
            log.warn(
                    "Skip registering {} invocation due to null argument(s): cacheName={}, key={}, invocationIsNull={}",
                    getInvocationType(),
                    cacheName,
                    key,
                    invocation == null);
            return;
        }

        Key normalizedKey = createKey(cacheName, key);
        invocations.put(normalizedKey, invocation);
        log.debug("Registered {} invocation for {}", getInvocationType(), normalizedKey);
    }

    /**
     * 获取调用映射
     * 
     * @param cacheName 缓存名称
     * @param key       缓存键
     * @return 调用实例的Optional包装
     */
    public Optional<T> get(String cacheName, @Nullable Object key) {
        if (cacheName == null) {
            log.warn("Skip getting {} invocation due to null cacheName", getInvocationType());
            return Optional.empty();
        }

        Key normalizedKey = createKey(cacheName, key);
        T invocation = invocations.get(normalizedKey);
        log.debug(
                "Lookup {} invocation for {} -> {}",
                getInvocationType(),
                normalizedKey,
                invocation != null ? "HIT" : "MISS");
        return Optional.ofNullable(invocation);
    }

    /**
     * 获取特定键的锁
     * 
     * @param cacheName 缓存名称
     * @param key       缓存键
     * @return ReentrantLock实例
     */
    public ReentrantLock obtainLock(String cacheName, @Nullable Object key) {
        Key normalizedKey = createKey(cacheName, key);
        ReentrantLock lock = keyLocks.computeIfAbsent(normalizedKey, k -> new ReentrantLock());
        log.debug("Obtained {} lock for {}", getInvocationType(), normalizedKey);
        return lock;
    }

    /**
     * 移除指定的调用映射和锁
     * 
     * @param cacheName 缓存名称
     * @param key       缓存键
     */
    public void remove(String cacheName, @Nullable Object key) {
        Key normalizedKey = createKey(cacheName, key);
        T removed = invocations.remove(normalizedKey);
        ReentrantLock lockRemoved = keyLocks.remove(normalizedKey);
        log.debug(
                "Removed {} registry entries for {} (invocationRemoved={}, lockRemoved={})",
                getInvocationType(),
                normalizedKey,
                removed != null,
                lockRemoved != null);
    }

    /**
     * 按缓存名称批量清理注册信息与本地锁
     * 
     * @param cacheName 缓存名称
     */
    public void removeAll(String cacheName) {
        if (cacheName == null) {
            log.warn("Skip removeAll {} registry due to null cacheName", getInvocationType());
            return;
        }

        log.info("Removing all {} registry entries and locks for cacheName={}", getInvocationType(), cacheName);
        invocations.keySet().removeIf(k -> cacheName.equals(k.cacheName()));
        keyLocks.keySet().removeIf(k -> cacheName.equals(k.cacheName()));
        log.debug("Completed {} removeAll for cacheName={}", getInvocationType(), cacheName);
    }

    /**
     * 创建标准化的Key实例
     * 子类可以覆盖此方法来实现特定的key处理逻辑
     * 
     * @param cacheName 缓存名称
     * @param key       原始键值
     * @return 标准化后的Key实例
     */
    protected Key createKey(String cacheName, @Nullable Object key) {
        return new Key(cacheName, key);
    }

    /**
     * 获取调用类型名称，用于日志输出
     * 
     * @return 调用类型名称
     */
    protected abstract String getInvocationType();
}
