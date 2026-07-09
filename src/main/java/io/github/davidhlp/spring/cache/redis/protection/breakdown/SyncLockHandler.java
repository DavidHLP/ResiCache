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
 * 同步锁处理器，防止缓存击穿 — ADR-0009 D6 后的精简形态。
 *
 * <p>职责：
 * <ul>
 *   <li>判断是否需要加锁</li>
 *   <li>如需加锁，在锁内通过 {@link ChainEngine#executeChainFragment} 推进剩余链</li>
 *   <li>锁逻辑完全集中在此 Handler，ActualCacheHandler 不再处理锁</li>
 * </ul>
 *
 * <p>ADR-0009 D6 改进（vs. 原 executeChainInLock 直接 getNext().handle()）+ ADR-0022 锚点更新：
 * <ul>
 *   <li>原：{@code executeChainInLock} 直接调 {@code getNext().handle(ctx)}，
 *       绕过基类 / Engine 的 skipRemaining 短路、shouldHandle 分发、perNode
 *       观测（DEBUG log / fired counter）— 锁内片段与主链行为有微妙分叉</li>
 *   <li>ADR-0009：{@code engine.executeChainFragment(ctx, getNext())} 走 Engine 统一
 *       推进协议，perNode 观测照常触发（DEBUG log / fired counter），aroundChain
 *       观测（MDC stamp / Timer record）由外层 execute 唯一负责，锁内不重复打点</li>
 *   <li>ADR-0022：next 指针链删除，调用改为 {@code engine.executeChainFragment(ctx, this)}
 *       —— Engine 按 snapshot {@code indexOf(this) + 1} 定位后继，语义等价（推进"自己
 *       之后"的剩余链）；handler 不再依赖自身在链中的 next 引用</li>
 *   <li>收益：锁内行为与主链一致；Engine 0 修改即可生效；WS-1.4 Span 在锁内
 *       也会按 perNode 出现 — 与原"只在外层打点"的不对称语义相比更可解释</li>
 * </ul>
 *
 * <p><b>ADR-0045</b>：原通过 {@code context.setAttribute("sync.lock.acquired", true)}
 * + 基类 {@code shouldHandle} 检测此 stringly-typed 标记的"重入守护"已删除 ——
 * 实际生产路径下,fragment 推进 ({@code engine.executeChainFragment(ctx, this)})
 * 按 ADR-0022 {@code indexOf(this) + 1} 定位后继,不会再回到 SyncLockHandler 自身,
 * 该标记属于 dead seam。删除后 locality 改善 + context 属性袋收窄。
 *
 * <p><b>锁超时解析收口</b>:原 {@code resolveTimeout} / {@code getGlobalTimeoutSeconds} +
 * {@code DEFAULT_LOCK_TIMEOUT} 已迁出到 {@link SyncLockTimeout} —— 与
 * {@code RedisProCache} loader 路径共享同一规则,消除两处分叉(loader 路径此前忽略全局配置)。
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
     * ADR-0018 — 语义 counter 元数据声明。WS-1.4 per-handler tag：
     * 分布式锁成功获取事件计数（sync=true 缓存操作进入临界区）。
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
        // ADR-0054:sync-lock 子集谓词(GET + PUT + PUT_IF_ABSENT),操作枚举承担单一真理源
        return context.getOperation().requiresSyncLock();
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        // D5 (Round 47):原 LockContext builder 已在 doHandle 入口一次性构造,无论
        // 后续是否需要锁都付出 builder 分配代价。改为 check-first → resolve-on-demand
        // 三步走,check 失败直接 continueChain,无 builder 分配。LockContext 记录
        // 因单一消费者 + 唯一调用方被 inline 消除,deletion test 通过。
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

        // WS-1.4 per-handler tag:分布式锁成功获取事件计数
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
