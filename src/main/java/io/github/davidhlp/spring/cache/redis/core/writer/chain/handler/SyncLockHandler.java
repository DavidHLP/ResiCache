package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Slf4j
@Component
@RequiredArgsConstructor
public class SyncLockHandler extends AbstractCacheHandler {

    private static final long DEFAULT_LOCK_TIMEOUT = 10;

	@Override
	protected boolean shouldHandle(CacheContext context) {
		if (context.getCacheOperation() == null || !context.getCacheOperation().isSync()) {
			return false;
		}
		CacheOperation operation = context.getOperation();
		return operation == CacheOperation.GET
				|| operation == CacheOperation.PUT_IF_ABSENT
				|| operation == CacheOperation.PUT;
	}

    @Override
    protected CacheResult doHandle(CacheContext context) {
        LockContext lockContext = initializeLockContext(context);
        context.setLockContext(lockContext);

        log.debug(
                "Prepared lock context for cache operation: cacheName={}, key={}, operation={}, timeout={}s",
                context.getCacheName(),
                lockContext.lockKey(),
                context.getOperation(),
                lockContext.timeoutSeconds());

        return invokeNext(context);
    }

    private long resolveTimeout(RedisCacheableOperation operation) {
        if (operation == null) {
            return DEFAULT_LOCK_TIMEOUT;
        }
        long timeout = operation.getSyncTimeout();
        return timeout > 0 ? timeout : DEFAULT_LOCK_TIMEOUT;
    }

    private LockContext initializeLockContext(CacheContext context) {
        Assert.notNull(context.getCacheOperation(), "Cache operation must not be null");
        String lockKey = context.getRedisKey();
        Assert.hasText(lockKey, "Lock key must not be empty");

        return LockContext.builder()
                .syncLock(context.getCacheOperation().isSync())
                .lockKey(lockKey)
                .timeoutSeconds(resolveTimeout(context.getCacheOperation()))
                .build();
    }
}
