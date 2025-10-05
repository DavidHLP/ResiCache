package com.david.spring.cache.redis.core.writer.chain.handler;

import com.david.spring.cache.redis.core.writer.chain.CacheOperation;
import com.david.spring.cache.redis.core.writer.chain.CacheResult;
import com.david.spring.cache.redis.core.writer.support.lock.SyncSupport;
import com.david.spring.cache.redis.register.operation.RedisCacheableOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sync lock handler guards hot key cache misses by leveraging multi-level locks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncLockHandler extends AbstractCacheHandler {

    private final SyncSupport syncSupport;

    private static final long DEFAULT_LOCK_TIMEOUT = 10;

    @Override
    protected boolean shouldHandle(CacheContext context) {
        return context.getCacheOperation() != null
                && context.getCacheOperation().isSync()
                && (context.getOperation() == CacheOperation.GET
                        || context.getOperation() == CacheOperation.PUT_IF_ABSENT);
    }

    @Override
    protected CacheResult doHandle(CacheContext context) {
        long timeoutSeconds = resolveTimeout(context.getCacheOperation());
        String lockKey = context.getRedisKey();

        log.debug(
                "Applying sync lock for cache operation: cacheName={}, key={}, operation={}, timeout={}s",
                context.getCacheName(),
                lockKey,
                context.getOperation(),
                timeoutSeconds);

        AtomicBoolean loaderInvoked = new AtomicBoolean(false);
        try {
            return syncSupport.executeSync(
                    lockKey,
                    () -> {
                        loaderInvoked.set(true);
                        return invokeNext(context);
                    },
                    timeoutSeconds);
        } catch (RuntimeException ex) {
            if (loaderInvoked.get()) {
                throw ex;
            }

            log.error(
                    "Sync lock execution failed before invoking downstream handler, falling back without lock: cacheName={}, key={}",
                    context.getCacheName(),
                    lockKey,
                    ex);
            return invokeNext(context);
        }
    }

    private long resolveTimeout(RedisCacheableOperation operation) {
        if (operation == null) {
            return DEFAULT_LOCK_TIMEOUT;
        }
        long timeout = operation.getSyncTimeout();
        return timeout > 0 ? timeout : DEFAULT_LOCK_TIMEOUT;
    }
}