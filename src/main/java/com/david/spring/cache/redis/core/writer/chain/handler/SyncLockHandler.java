package com.david.spring.cache.redis.core.writer.chain.handler;

import com.david.spring.cache.redis.core.writer.chain.CacheOperation;
import com.david.spring.cache.redis.core.writer.chain.CacheResult;
import com.david.spring.cache.redis.core.writer.support.lock.SyncSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 同步锁处理器 职责：防止缓存击穿（热点 key 并发问题） - GET/PUT_IF_ABSENT 操作：使用两级锁（本地锁 + 分布式锁）保证只有一个线程/实例加载缓存
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncLockHandler extends AbstractCacheHandler {

    private final SyncSupport syncSupport;

    /** 默认锁超时时间（秒） */
    private static final long DEFAULT_LOCK_TIMEOUT = 10;

    @Override
    protected boolean shouldHandle(CacheContext context) {
        // 检查是否启用了 sync 模式
        return context.getCacheOperation() != null
                && context.getCacheOperation().isSync()
                && (context.getOperation() == CacheOperation.GET
                        || context.getOperation() == CacheOperation.PUT_IF_ABSENT);
    }

    @Override
    protected CacheResult doHandle(CacheContext context) {
        log.debug(
                "Using sync mode for cache operation: cacheName={}, key={}, operation={}",
                context.getCacheName(),
                context.getRedisKey(),
                context.getOperation());

        try {
            // 使用同步锁执行后续的责任链
            return syncSupport.executeSync(
                    context.getRedisKey(), () -> invokeNext(context), DEFAULT_LOCK_TIMEOUT);
        } catch (Exception e) {
            log.error(
                    "Failed to execute with sync lock: cacheName={}, key={}",
                    context.getCacheName(),
                    context.getRedisKey(),
                    e);
            // 即使锁失败，也继续执行（降级策略）
            return invokeNext(context);
        }
    }
}
