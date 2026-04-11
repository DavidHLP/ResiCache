package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.core.writer.support.lock.SyncSupport;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 同步锁处理器，防止缓存击穿
 *
 * <p>职责：
 * <ul>
 *   <li>判断是否需要加锁</li>
 *   <li>如需加锁，在锁内执行后续 Handler</li>
 *   <li>锁逻辑完全集中在此 Handler，ActualCacheHandler 不再处理锁</li>
 * </ul>
 *
 * <p>设计改进：
 * <ul>
 *   <li>原设计：SyncLockHandler 只准备 LockContext，ActualCacheHandler 执行锁</li>
 *   <li>新设计：SyncLockHandler 直接包装后续链的执行，锁逻辑内聚</li>
 *   <li>通过标记 `lockAcquired` 避免下游 Handler 重复加锁</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@HandlerPriority(HandlerOrder.SYNC_LOCK)
public class SyncLockHandler extends AbstractCacheHandler {

    /** 上下文属性键：标记锁已获取 */
    private static final String LOCK_ACQUIRED_KEY = "sync.lock.acquired";

    private static final long DEFAULT_LOCK_TIMEOUT = 10;

    private final SyncSupport syncSupport;

    @Override
    protected boolean shouldHandle(CacheContext context) {
        // 检查是否已被上游处理
        if (context.getAttribute(LOCK_ACQUIRED_KEY, false)) {
            return false;
        }

        if (context.getCacheOperation() == null || !context.getCacheOperation().isSync()) {
            return false;
        }
        CacheOperation operation = context.getOperation();
        return operation == CacheOperation.GET
                || operation == CacheOperation.PUT_IF_ABSENT
                || operation == CacheOperation.PUT;
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        LockContext lockContext = createLockContext(context);

        // 判断是否需要锁
        if (!lockContext.requiresLock()) {
            log.debug("Sync enabled but lock not required, continuing chain: cacheName={}, key={}",
                      context.getCacheName(), context.getRedisKey());
            return HandlerResult.continueChain();
        }

        log.debug("Executing with sync lock: cacheName={}, key={}, timeout={}s",
                  context.getCacheName(), lockContext.lockKey(), lockContext.timeoutSeconds());

        // 标记锁已获取（防止下游重复加锁）
        context.setAttribute(LOCK_ACQUIRED_KEY, true);

        // 在锁内执行后续 Handler
        CacheResult result = syncSupport.executeSync(
            lockContext.lockKey(),
            () -> executeChainInLock(context),
            lockContext.timeoutSeconds()
        );

        // 锁内执行完成，终止链
        return HandlerResult.terminate(result);
    }

    /**
     * 在锁内执行后续责任链
     *
     * <p>注意：由于已经标记了 LOCK_ACQUIRED_KEY，下游 Handler 的 shouldHandle()
     * 会返回 false，避免重复处理。
     */
    private CacheResult executeChainInLock(CacheContext context) {
        // 继续执行后续 Handler
        if (getNext() != null) {
            HandlerResult nextResult = getNext().handle(context);
            CacheResult result = nextResult.result();
            return result != null ? result : CacheResult.success();
        }
        return CacheResult.success();
    }

    /**
     * 创建锁上下文
     */
    private LockContext createLockContext(CacheContext context) {
        RedisCacheableOperation operation = context.getCacheOperation();
        Assert.notNull(operation, "Cache operation must not be null");

        String lockKey = context.getRedisKey();
        Assert.hasText(lockKey, "Lock key must not be empty");

        long timeout = resolveTimeout(operation);

        return LockContext.builder()
                .syncLock(operation.isSync())
                .lockKey(lockKey)
                .timeoutSeconds(timeout)
                .build();
    }

    /**
     * 解析锁超时时间
     */
    private long resolveTimeout(RedisCacheableOperation operation) {
        if (operation == null) {
            return DEFAULT_LOCK_TIMEOUT;
        }
        long timeout = operation.getSyncTimeout();
        return timeout > 0 ? timeout : DEFAULT_LOCK_TIMEOUT;
    }
}
