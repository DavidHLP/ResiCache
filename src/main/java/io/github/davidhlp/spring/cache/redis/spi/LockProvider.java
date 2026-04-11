package io.github.davidhlp.spring.cache.redis.spi;

/**
 * 分布式锁 SPI 接口
 *
 * <p>提供可插拔的分布式锁实现，允许用户替换默认的 Redisson 实现。
 *
 * <p>使用方式：
 * <ol>
 *   <li>实现此接口创建自定义锁管理器</li>
 *   <li>在 `META-INF/services/io.github.davidhlp.spring.cache.redis.spi.LockProvider` 文件中注册</li>
 *   <li>设置 `resi-cache.sync-lock.provider=custom` 启用自定义实现</li>
 * </ol>
 */
public interface LockProvider {

    /**
     * 获取锁管理器实例
     *
     * @return 锁管理器
     */
    LockManager create();

    /**
     * 获取 Provider 名称
     *
     * @return 实现名称，用于配置匹配
     */
    String getName();
}
