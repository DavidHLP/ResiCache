package io.github.davidhlp.spring.cache.redis.protection.breakdown;

import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.model.*;


import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.ChainEngine;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 同步锁处理器，防止缓存击穿 — ADR-0009 D6 后的精简形态。
 *
 * <p>职责：
 * <ul>
 *   <li>判断是否需要加锁</li>
 *   <li>如需加锁，在锁内通过 {@link ChainEngine#executeChainFragment} 推进剩余链</li>
 *   <li>锁逻辑完全集中在此 Handler，ActualCacheHandler 不再处理锁</li>
 * </ul>
 *
 * <p>ADR-0009 D6 改进（vs. 原 executeChainInLock 直接 getNext().handle()）：
 * <ul>
 *   <li>原：{@code executeChainInLock} 直接调 {@code getNext().handle(ctx)}，
 *       绕过基类 / Engine 的 skipRemaining 短路、shouldHandle 分发、perNode
 *       观测（DEBUG log / fired counter）— 锁内片段与主链行为有微妙分叉</li>
 *   <li>新：{@code engine.executeChainFragment(ctx, getNext())} 走 Engine 统一
 *       推进协议，perNode 观测照常触发（DEBUG log / fired counter），aroundChain
 *       观测（MDC stamp / Timer record）由外层 execute 唯一负责，锁内不重复打点</li>
 *   <li>收益：锁内行为与主链一致；Engine 0 修改即可生效；WS-1.4 Span 在锁内
 *       也会按 perNode 出现 — 与原"只在外层打点"的不对称语义相比更可解释</li>
 * </ul>
 *
 * <p>通过标记 {@code lockAcquired} 避免下游 Handler 重复加锁（基类
 * {@code shouldHandle} 检测此标记后直接返 false）。
 */
@Slf4j
@Component
@HandlerPriority(HandlerOrder.SYNC_LOCK)
public class SyncLockHandler extends AbstractCacheHandler {

    /** 上下文属性键：标记锁已获取。 */
    private static final String LOCK_ACQUIRED_KEY = "sync.lock.acquired";

    private static final long DEFAULT_LOCK_TIMEOUT = 10;

    private final SyncSupport syncSupport;

    private final RedisProCacheProperties properties;

    /** 推进引擎 — 由 Spring 注入（{@code @Autowired} 字段注入），锁内片段推进用
     * {@link ChainEngine#executeChainFragment}。测试可通过 {@link #setEngine(ChainEngine)}
     * 显式注入。 */
    @Autowired
    private ChainEngine engine;

    /**
     * 测试用 setter — 显式注入 ChainEngine 避免 {@code @Autowired} 反射依赖。
     * 生产环境由 Spring 容器自动注入。
     *
     * @param engine 推进引擎（不为 null）
     */
    void setEngine(ChainEngine engine) {
        this.engine = engine;
    }

    /** 分布式锁成功获取事件计数（语义 counter）。 */
    private Counter lockAcquiredCounter;

    public SyncLockHandler(SyncSupport syncSupport,
                           RedisProCacheProperties properties) {
        this.syncSupport = syncSupport;
        this.properties = properties;
    }

    @Override
    protected void onAttachMetrics(MeterRegistry registry) {
        this.lockAcquiredCounter = registerCounter(registry,
                "resicache.handler.sync.lock.acquired",
                "Distributed lock acquired (sync=true cache operation entered critical section)");
    }

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

        // WS-1.4 per-handler tag:分布式锁成功获取事件计数
        safeIncrement(lockAcquiredCounter);

        // 在锁内执行后续 Handler — 委派给 Engine 统一推进（perNode 观测照常，
        // aroundChain 观测由外层 execute 唯一负责，锁内不重复打点）
        CacheResult result = syncSupport.executeSync(
            lockContext.lockKey(),
            () -> engine.executeChainFragment(context, getNext()),
            lockContext.timeoutSeconds()
        );

        // 锁内执行完成，终止链
        return HandlerResult.terminate(result);
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
     * 解析锁超时时间 — 保留原全局回退路径（{@code properties.getSyncLock().getTimeout()}），
     * 未在注解上显式覆盖时退回全局配置；均无则用 {@link #DEFAULT_LOCK_TIMEOUT}。
     */
    private long resolveTimeout(RedisCacheableOperation operation) {
        if (operation == null) {
            return getGlobalTimeoutSeconds();
        }
        long timeout = operation.getSyncTimeout();
        if (timeout < 0) {
            return getGlobalTimeoutSeconds();
        }
        return timeout > 0 ? timeout : DEFAULT_LOCK_TIMEOUT;
    }

    /**
     * 获取全局配置的锁超时时间（秒） — 调用方通常为 {@link #resolveTimeout} 的注解 timeout < 0 回退。
     */
    private long getGlobalTimeoutSeconds() {
        long timeout = properties.getSyncLock().getTimeout();
        java.util.concurrent.TimeUnit unit = properties.getSyncLock().getUnit();
        long seconds = unit.toSeconds(timeout);
        return seconds > 0 ? seconds : DEFAULT_LOCK_TIMEOUT;
    }
}
