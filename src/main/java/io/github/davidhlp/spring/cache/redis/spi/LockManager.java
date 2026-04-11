package io.github.davidhlp.spring.cache.redis.spi;

/**
 * 锁管理器接口
 *
 * <p>定义分布式锁的基本操作，用于防止缓存击穿。
 */
public interface LockManager {

    /**
     * 尝试获取锁
     *
     * @param key 锁的键名
     * @param timeoutSeconds 获取锁的超时时间
     * @return 锁句柄，如果不支持或获取失败返回空
     */
    java.util.Optional<LockHandle> tryAcquire(String key, long timeoutSeconds) throws InterruptedException;

    /**
     * 获取排序顺序
     *
     * @return 顺序值
     */
    default int getOrder() {
        return 0;
    }
}
