package io.github.davidhlp.spring.cache.redis.protection.breakdown;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 分布式锁超时解析的单一 seam.
 *
 * <p>「一次 sync 缓存操作允许 leader 持锁多久」是一条领域规则,此前散落在两个模块且行为分叉:
 * <ul>
 *   <li>{@code SyncLockHandler.resolveTimeout}(责任链 GET/PUT/PUT_IF_ABSENT 路径):
 *       注解 {@code < 0} → 退回全局配置;{@code == 0} → 默认 10s;{@code > 0} → 取注解值</li>
 *   <li>{@code RedisProCache.resolveSyncTimeout}(Spring Cache loader 回源路径):
 *       {@code > 0} → 取注解值;否则一律硬编码 10s —— <b>全局配置
 *       {@code resi-cache.sync-lock.timeout} 被静默忽略</b></li>
 * </ul>
 * 两处喂给的是<em>同一把分布式锁</em>({@link SyncSupport#executeSync}),却可能算出不同的超时,
 * 且 loader 路径丢弃了用户配置。本类把该规则收口到唯一入口,消除分叉并兑现全局配置。
 *
 * <p><b>解析规则</b>(注解 {@code syncTimeout} 语义):
 * <ol>
 *   <li>{@code > 0} —— 注解显式覆盖,直接取该秒数</li>
 *   <li>{@code == 0} —— 取内置默认 {@link #DEFAULT_LOCK_TIMEOUT_SECONDS}</li>
 *   <li>{@code < 0}(或 operation 为 null)—— 退回全局配置
 *       {@code resi-cache.sync-lock.timeout}(按其 {@code unit} 归一化为秒);
 *       全局值非正时兜底默认</li>
 * </ol>
 */
@Component
public class SyncLockTimeout {

    /** 注解与全局配置均未给出有效值时的兜底锁超时(秒). */
    public static final long DEFAULT_LOCK_TIMEOUT_SECONDS = 10L;

    private final RedisProCacheProperties properties;

    public SyncLockTimeout(RedisProCacheProperties properties) {
        this.properties = properties;
    }

    /**
     * 解析给定 operation 的锁超时(秒).
     *
     * @param operation 方法级 operation(可为 null,视作「未覆盖」→ 全局配置)
     * @return 锁超时秒数(恒为正)
     */
    public long resolveSeconds(@Nullable RedisCacheableOperation operation) {
        long timeout = operation == null ? -1L : operation.getSyncTimeout();
        if (timeout > 0) {
            return timeout;
        }
        if (timeout == 0) {
            return DEFAULT_LOCK_TIMEOUT_SECONDS;
        }
        return globalSeconds();
    }

    /** 全局配置超时归一化为秒;非正时兜底默认. */
    private long globalSeconds() {
        RedisProCacheProperties.SyncLockProperties syncLock = properties.getSyncLock();
        long seconds = syncLock.getUnit().toSeconds(syncLock.getTimeout());
        return seconds > 0 ? seconds : DEFAULT_LOCK_TIMEOUT_SECONDS;
    }
}
