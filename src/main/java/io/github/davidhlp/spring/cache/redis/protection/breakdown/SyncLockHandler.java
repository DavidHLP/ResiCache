package io.github.davidhlp.spring.cache.redis.protection.breakdown;

import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.model.*;


import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.ChainEngine;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 同步锁处理器，防止缓存击穿。
 *
 * <p>职责：
 * <ul>
 *   <li>判断是否需要加锁</li>
 *   <li>如需加锁，在锁内通过 {@link ChainEngine#executeChainFragment} 推进剩余链</li>
 *   <li>锁逻辑完全集中在此 Handler，ActualCacheHandler 不处理锁</li>
 * </ul>
 *
 * <p>锁内片段推进走 Engine 统一协议({@code engine.executeChainFragment(ctx, this)},
 * 按 snapshot {@code indexOf(this) + 1} 定位后继):
 * <ul>
 *   <li>perNode 观测(DEBUG log / fired counter)照常触发;aroundChain 观测
 *       (MDC stamp / Timer record)由外层 execute 唯一负责,锁内不重复打点</li>
 *   <li>handler 不依赖自身在链中的 next 引用;fragment 按 {@code indexOf(this) + 1}
 *       定位后继,不会再回到本 handler 自身</li>
 *   <li>锁内行为与主链一致</li>
 * </ul>
 *
 * <p><b>锁超时解析</b>:由 {@link SyncLockTimeout} 统一承担,与 {@code RedisProCache}
 * loader 路径共享同一规则,避免分叉。
 */
@Slf4j
@Component
@HandlerPriority(HandlerOrder.SYNC_LOCK)
public class SyncLockHandler extends AbstractCacheHandler {

    private final SyncSupport syncSupport;

    private final SyncLockTimeout syncLockTimeout;

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

    public SyncLockHandler(SyncSupport syncSupport,
                           SyncLockTimeout syncLockTimeout) {
        this.syncSupport = syncSupport;
        this.syncLockTimeout = syncLockTimeout;
    }

    /**
     * 语义 counter 元数据声明:分布式锁成功获取事件计数(sync=true 缓存操作进入临界区)。
     */
    @Override
    protected CounterMetadata semanticCounter() {
        return new CounterMetadata(
                "resicache.handler.sync.lock.acquired",
                "Distributed lock acquired (sync=true cache operation entered critical section)");
    }

    @Override
    protected boolean shouldHandle(CacheContext context) {
        if (context.getCacheOperation() == null || !context.getCacheOperation().isSync()) {
            return false;
        }
        // sync-lock 子集谓词(GET + PUT + PUT_IF_ABSENT),操作枚举承担单一真理源
        return context.getOperation().requiresSyncLock();
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        // check-first → resolve-on-demand:check 失败直接 continueChain,避免 builder 分配。
        // LockContext 因单一消费者被 inline,不单独成类。
        RedisCacheableOperation operation = context.getCacheOperation();
        Assert.notNull(operation, "Cache operation must not be null");
        String lockKey = context.getRedisKey();
        Assert.hasText(lockKey, "Lock key must not be empty");

        if (!operation.isSync()) {
            log.debug("Sync enabled but lock not required, continuing chain: cacheName={}, key={}",
                      context.getCacheName(), context.getRedisKey());
            return HandlerResult.continueChain();
        }

        long timeout = syncLockTimeout.resolveSeconds(operation);

        log.debug("Executing with sync lock: cacheName={}, key={}, timeout={}s",
                  context.getCacheName(), lockKey, timeout);

        // 分布式锁成功获取事件计数
        safeIncrementSemantic();

        // 在锁内执行后续 Handler — 委派给 Engine 统一推进(perNode 观测照常,
        // aroundChain 观测由外层 execute 唯一负责,锁内不重复打点)
        CacheResult result = syncSupport.executeSync(
            lockKey,
            () -> engine.executeChainFragment(context, this),
            timeout
        );

        // 锁内执行完成,终止链
        return HandlerResult.terminate(result);
    }
}
