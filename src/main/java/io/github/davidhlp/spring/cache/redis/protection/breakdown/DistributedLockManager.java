package io.github.davidhlp.spring.cache.redis.protection.breakdown;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Redisson 的分布式锁管理器。将分布式锁相关的逻辑从 SyncSupport 中分离出来。
 *
 * <p><b>Cluster hash-tag pinning (WS-1.2b)</b>:在 Redis Cluster 模式下,锁 key 会与
 * 缓存 key 落在同一 slot(通过 Redis hash-tag {@code {...}}),避免锁与所保护数据分散到不同
 * 节点。虽然 Redisson 锁在跨 slot 时仍有效,但同 slot 保证了"锁与数据同节点"的语义,且使未来
 * 任何锁内 MULTI/事务(要求同 slot)不会因 cross-slot 失败。single/sentinel 模式保持
 * {@code prefix + key} 不变(hash-tag 在非 cluster 下无意义)。
 */
@Slf4j
@Component("distributedLockManager")
@ConditionalOnClass(RedissonClient.class)
@RequiredArgsConstructor
public class DistributedLockManager implements LockManager {

    /** 锁持有超时倍数，leaseTime = timeoutSeconds * LEASE_TIMEOUT_MULTIPLIER */
    private static final long LEASE_TIMEOUT_MULTIPLIER = 3;

    /** 最小锁持有时间（秒），确保即使 timeoutSeconds 很小也有足够的 lease time */
    private static final long MIN_LEASE_TIME_SECONDS = 10;

    /** 锁释放重试最大次数 */
    private static final int MAX_UNLOCK_RETRIES = 3;

    /** 锁释放重试间隔（毫秒） */
    private static final long UNLOCK_RETRY_INTERVAL_MS = 100;

    private final RedissonClient redissonClient;
    private final RedisProCacheProperties properties;

    /**
     * 尝试获取指定键的分布式锁.
     *
     * @param key            锁的键名（即缓存 key）
     * @param timeoutSeconds 获取锁的超时时间（秒）
     * @return 如果成功获取锁则返回包含锁句柄的Optional，否则返回空的Optional
     * @throws InterruptedException 如果等待锁的过程中线程被中断
     */
    @Override
    public Optional<LockHandle> tryAcquire(final String key, final long timeoutSeconds) throws InterruptedException {
        String lockKey = buildLockKey(key);
        RLock lock = redissonClient.getLock(lockKey);

        // 计算合理的 lease time，确保即使持有者崩溃也能自动释放
        long leaseTimeSeconds = Math.max(
                MIN_LEASE_TIME_SECONDS,
                timeoutSeconds * LEASE_TIMEOUT_MULTIPLIER
        );

        try {
            boolean acquired = lock.tryLock(timeoutSeconds, leaseTimeSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn(
                        "Failed to acquire distributed lock for key within {} seconds: {} (lockKey={})",
                        timeoutSeconds,
                        key,
                        lockKey);
                return Optional.empty();
            }

            log.debug("Acquired distributed lock for key: {}, lockKey: {}, leaseTime: {}s",
                    key, lockKey, leaseTimeSeconds);
            return Optional.of(new RedissonLockHandle(lock, key));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for distributed lock on key: {}", key, e);
            throw new RuntimeException("Interrupted while waiting for distributed lock on key: " + key, e);
        }
    }

    /**
     * 构造锁 key,确保 Cluster 模式下与缓存 key 落在同一 slot (WS-1.2b).
     *
     * <p>Redis Cluster 用 hash-tag(第一个 '{@code {{ }}' 到其后第一个 '{@code }}' 之间的内容)计算 slot。
     * 策略:
     * <ul>
     *   <li>非 Cluster 模式(single/sentinel):保持 {@code prefix + key}(hash-tag 无意义)</li>
     *   <li>Cluster + key 已含 hash-tag:保留 key 的 hash-tag({@code prefix + key},
     *       前提是 prefix 不含 '{@code {{ }',否则需用户避免)</li>
     *   <li>Cluster + key 无 hash-tag:用 {@code prefix + "{" + key + "}"} 包裹整个 key,
     *       使锁 key 的 slot = CRC16(key) = 缓存 key 的 slot</li>
     * </ul>
     *
     * <p>package-private 以便单元测试直接验证 hash-tag / slot 逻辑(用 lettuce
     * {@code SlotHash.getSlot} 权威校验同 slot)。
     *
     * @param key 缓存 key
     * @return 锁 key(Cluster 模式下与缓存 key 同 slot)
     */
    String buildLockKey(final String key) {
        final String prefix = properties.getSyncLock().getPrefix();
        if (!isClusterMode()) {
            return prefix + key;
        }
        // Cluster 模式:保留 key 自身的 hash-tag(若有);否则包裹整个 key 使 slot 与缓存 key 一致
        if (hasHashTag(key)) {
            return prefix + key;
        }
        return prefix + "{" + key + "}";
    }

    private boolean isClusterMode() {
        return "cluster".equalsIgnoreCase(properties.getRedis().getMode());
    }

    /**
     * 判断 key 是否含有效的 Redis hash-tag(第一个 '{' 之后存在非空的 '}' 内容).
     *
     * @param key 待检查的 key
     * @return 含 {@code {non-empty}} 返回 true,否则 false
     */
    private static boolean hasHashTag(final String key) {
        final int begin = key.indexOf('{');
        if (begin < 0) {
            return false;
        }
        final int end = key.indexOf('}', begin + 1);
        return end > begin + 1;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    /**
     * 基于Redisson的锁句柄实现
     */
    private static final class RedissonLockHandle implements LockHandle {

        private final RLock lock;
        private final String key;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private RedissonLockHandle(final RLock lock, final String key) {
            this.lock = lock;
            this.key = key;
        }

        /**
         * 释放分布式锁
         * 只有持有锁的线程才能释放锁，且每个锁只能被释放一次
         */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            if (!lock.isHeldByCurrentThread()) {
                return;
            }

            for (int attempt = 1; attempt <= MAX_UNLOCK_RETRIES; attempt++) {
                try {
                    lock.unlock();
                    log.debug("Released distributed lock for key: {} on attempt {}", key, attempt);
                    return;
                } catch (Exception e) {
                    if (attempt == MAX_UNLOCK_RETRIES) {
                        log.error("Failed to release distributed lock for key: {} after {} attempts",
                                key, MAX_UNLOCK_RETRIES, e);
                        return;
                    }
                    log.warn("Failed to release distributed lock for key: {} on attempt {}, retrying in {}ms",
                            key, attempt, UNLOCK_RETRY_INTERVAL_MS);
                    try {
                        Thread.sleep(UNLOCK_RETRY_INTERVAL_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Interrupted while retrying lock release for key: {}", key, ie);
                        return;
                    }
                }
            }
        }
    }
}
