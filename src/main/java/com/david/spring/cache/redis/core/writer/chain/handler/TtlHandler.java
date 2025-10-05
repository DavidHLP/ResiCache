package com.david.spring.cache.redis.core.writer.chain.handler;

import com.david.spring.cache.redis.core.writer.chain.CacheOperation;
import com.david.spring.cache.redis.core.writer.chain.CacheResult;
import com.david.spring.cache.redis.core.writer.support.protect.ttl.TtlPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * TTL handler responsible for calculating final TTL values.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TtlHandler extends AbstractCacheHandler {

    private final TtlPolicy ttlPolicy;

    private static final long DEFAULT_TTL = 60;

    @Override
    protected boolean shouldHandle(CacheContext context) {
        return context.getOperation() == CacheOperation.PUT
                || context.getOperation() == CacheOperation.PUT_IF_ABSENT;
    }

    @Override
    protected CacheResult doHandle(CacheContext context) {
        calculateTtl(context);
        return invokeNext(context);
    }

    private void calculateTtl(CacheContext context) {
        Duration ttl = context.getTtl();
        if (ttl == null) {
            ttl = Duration.ofSeconds(DEFAULT_TTL);
        }

        if (context.getCacheOperation() != null
                && context.getCacheOperation().getTtl() > 0) {
            long finalTtl =
                    ttlPolicy.calculateFinalTtl(
                            context.getCacheOperation().getTtl(),
                            context.getCacheOperation().isRandomTtl(),
                            context.getCacheOperation().getVariance());

            context.setFinalTtl(finalTtl);
            context.setShouldApplyTtl(true);
            context.setTtlFromContext(true);

            log.debug(
                    "Using context TTL configuration: cacheName={}, key={}, baseTtl={}s, finalTtl={}s, randomTtl={}, variance={}",
                    context.getCacheName(),
                    context.getRedisKey(),
                    context.getCacheOperation().getTtl(),
                    finalTtl,
                    context.getCacheOperation().isRandomTtl(),
                    context.getCacheOperation().getVariance());
        } else if (ttlPolicy.shouldApply(ttl)) {
            long finalTtl = ttl.getSeconds();
            context.setFinalTtl(finalTtl);
            context.setShouldApplyTtl(true);
            context.setTtlFromContext(false);

            log.debug(
                    "Using parameter TTL: cacheName={}, key={}, ttl={}s",
                    context.getCacheName(),
                    context.getRedisKey(),
                    finalTtl);
        } else {
            context.setFinalTtl(-1);
            context.setShouldApplyTtl(false);
            context.setTtlFromContext(false);

            log.debug(
                    "No TTL applied: cacheName={}, key={}",
                    context.getCacheName(),
                    context.getRedisKey());
        }
    }
}