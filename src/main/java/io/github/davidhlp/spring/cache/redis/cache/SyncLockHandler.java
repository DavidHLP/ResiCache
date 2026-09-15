package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.ChainContinuation;
import io.github.davidhlp.spring.cache.redis.chain.HandlerOrder;
import io.github.davidhlp.spring.cache.redis.chain.HandlerPriority;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 同步锁处理器，防止缓存击穿。
 *
 * <p>职责：
 * <ul>
 *   <li>判断是否需要加锁</li>
 *   <li>如需加锁，在锁内用引擎交出的 {@link ChainContinuation} 推进剩余链</li>
 *   <li>锁逻辑完全集中在此 Handler，ActualCacheHandler 不处理锁</li>
 * </ul>
 *
 * <p>锁内推进走引擎显式交出的推进句柄({@code next.advance()}):
 * <ul>
 *   <li>perNode 观测(DEBUG log / fired counter)照常触发;aroundChain 观测
 *       (MDC stamp / Timer record)由外层 execute 唯一负责,锁内不重复打点</li>
 *   <li>句柄由 Engine 按<b>本节点在快照中的位置</b>构造,handler 不反查引擎、不依赖自身
 *       在链中的 next 引用,不会再回到本 handler 自身</li>
 *   <li>锁内行为与主链一致;读走 single-flight(并发共享 leader 结果),写走独占执行
 *       (并发写互斥但各自执行,不被 follower 合并)</li>
 * </ul>
 *
 * <p><b>锁超时解析</b>:由 {@link SyncLockTimeout} 统一承担,与 {@code RedisProCache}
 * loader 路径共享同一规则,避免分叉。
 */
@Slf4j
@Component
@HandlerPriority(HandlerOrder.SYNC_LOCK)
class SyncLockHandler extends AbstractCacheHandler {

    private final SyncSupport syncSupport;

    private final SyncLockTimeout syncLockTimeout;

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
        // 经稳定 CachePolicyView 读取(不依赖内部 RedisCacheableOperation)
        if (!context.policy().sync()) {
            return false;
        }
        // sync-lock 子集谓词(GET + PUT + PUT_IF_ABSENT),操作枚举承担单一真理源
        return context.getOperation().requiresSyncLock();
    }

    /**
     * 单参形态不在本 handler 支持范围 —— 锁内推进必须有引擎交出的
     * {@link ChainContinuation}。引擎始终经
     * {@link #doHandle(CacheContext, ChainContinuation)} 调用本节点,故此处只在「handler
     * 被脱离责任链直接调用」时命中:直接拒绝,而不是静默地只跑半个链。
     */
    @Override
    protected HandlerResult doHandle(CacheContext context) {
        throw new IllegalStateException(
                "SyncLockHandler requires a ChainContinuation; it must run inside the handler chain");
    }

    /**
     * 锁内推进形态 — 与 {@link #doHandle(CacheContext)} 同一决策链,区别是剩余链在
     * 分布式锁内由引擎交出的 {@link ChainContinuation} 推进。
     *
     * <p>引擎经 {@code handle(ctx, next)} 调用本方法;{@code next} 在构造期已绑定本节点在
     * 快照中的位置,故推进起点不再依赖 {@code indexOf(this)} 反查,也不需要任何 ThreadLocal。
     */
    @Override
    protected HandlerResult doHandle(CacheContext context, ChainContinuation next) {
        // check-first → resolve-on-demand:check 失败直接 continueChain,避免 builder 分配。
        CachePolicyView policy = context.policy();
        if (!policy.sync()) {
            log.debug("Sync enabled but lock not required, continuing chain: cacheName={}, key={}",
                      context.getCacheName(), context.getRedisKey());
            return HandlerResult.continueChain();
        }

        String lockKey = context.getRedisKey();
        Assert.hasText(lockKey, "Lock key must not be empty");

        long timeout = syncLockTimeout.resolveSeconds(policy.syncTimeoutSeconds());

        log.debug("Executing with sync lock: cacheName={}, key={}, timeout={}s",
                  context.getCacheName(), lockKey, timeout);

        // 分布式锁成功获取事件计数
        safeIncrementSemantic();

        // 在锁内执行后续 Handler — 用引擎交出的推进句柄驱动(perNode 观测照常,
        // aroundChain 观测由外层 execute 唯一负责,锁内不重复打点)。
        // 写路径走独占执行:写不能 join 他线程的 single-flight 结果(否则本笔写被静默丢弃)。
        CacheResult result = context.getOperation().isWrite()
                ? syncSupport.executeExclusive(lockKey, next::advance, timeout)
                : syncSupport.executeSync(lockKey, next::advance, timeout);

        // 锁内执行完成,终止链
        return HandlerResult.terminate(result);
    }
}
