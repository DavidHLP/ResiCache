package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import lombok.Builder;

/**
 * 锁上下文
 *
 * <p>封装同步锁的相关信息。
 * <p>SyncLockHandler 创建此对象，用于描述锁的状态。
 */
@Builder
public record LockContext(
    /** 是否启用同步锁 */
    boolean syncLock,

    /** 锁的 key */
    String lockKey,

    /** 锁超时时间（秒） */
    long timeoutSeconds
) {
    /**
     * 判断是否需要加锁
     *
     * @return true 表示需要加锁
     */
    public boolean requiresLock() {
        return syncLock && lockKey != null && !lockKey.isBlank();
    }

    /**
     * 创建锁上下文
     *
     * @param syncLock 是否启用同步锁
     * @param lockKey 锁的 key
     * @param timeoutSeconds 超时时间
     * @return LockContext 实例
     */
    public static LockContext of(boolean syncLock, String lockKey, long timeoutSeconds) {
        return LockContext.builder()
                .syncLock(syncLock)
                .lockKey(lockKey)
                .timeoutSeconds(timeoutSeconds)
                .build();
    }

    /**
     * 创建一个"不需要锁"的上下文
     *
     * <p>用于明确表示某个操作不需要加锁。
     */
    public static LockContext noLock() {
        return LockContext.builder()
                .syncLock(false)
                .lockKey(null)
                .timeoutSeconds(0)
                .build();
    }
}
