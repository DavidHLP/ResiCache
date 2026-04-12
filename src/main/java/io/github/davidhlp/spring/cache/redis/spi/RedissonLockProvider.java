package io.github.davidhlp.spring.cache.redis.spi;

import io.github.davidhlp.spring.cache.redis.core.writer.support.lock.DistributedLockManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 基于 Redisson 的分布式锁 SPI 实现。
 *
 * <p>通过 {@link DistributedLockManager} 提供 Redisson 分布式锁能力。
 */
@Slf4j
@Component
public class RedissonLockProvider {

    private final DistributedLockManager distributedLockManager;

    public RedissonLockProvider(DistributedLockManager distributedLockManager) {
        this.distributedLockManager = distributedLockManager;
    }

    public DistributedLockManager getLockManager() {
        return distributedLockManager;
    }
}
