package io.github.davidhlp.spring.cache.redis.spi;

/**
 * 锁句柄接口
 *
 * <p>持有锁的引用，在关闭时释放锁。
 */
public interface LockHandle extends AutoCloseable {

    /**
     * 释放锁
     */
    @Override
    void close();
}
