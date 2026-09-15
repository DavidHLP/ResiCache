package io.github.davidhlp.spring.cache.redis.cache;




import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.HandlerOrder;
import io.github.davidhlp.spring.cache.redis.chain.HandlerPriority;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.EarlyExpirationDecision;
import io.github.davidhlp.spring.cache.redis.chain.model.PrefetchDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 提前过期处理器，防止缓存雪崩 —— 链节点适配层。
 *
 * <p>职责只剩「链协议翻译」：
 * <ul>
 *   <li>判定本节点是否适用({@link #shouldHandle}:GET 且启用提前过期)</li>
 *   <li>向 {@link EarlyRefresh} 取一次评估(读值 + 判定 + 必要时调度),把结果写成类型化
 *       {@link PrefetchDecision} 供 {@link ActualCacheHandler} 复用,避免二次 Redis GET</li>
 *   <li>同步刷新场景返回 {@link HandlerResult#skipAll()},由 ActualCacheHandler 检查标记后返回 miss</li>
 * </ul>
 *
 * <p><b>读值 / 判定 / 调度 / Lua CAS 全在 {@link EarlyRefresh} 内</b>：本类不再持有
 * {@code RedisTemplate} / {@code ValueOperations} / {@code CacheStatisticsCollector},
 * 也不再有异步任务体与脚本调用 —— 那三者与「决策读到的值」是同一生命周期,不属于链节点职责。
 */
@Slf4j
@Component
@HandlerPriority(HandlerOrder.EARLY_EXPIRATION)
class EarlyExpirationHandler extends AbstractCacheHandler {

    private final EarlyRefresh earlyRefresh;

    EarlyExpirationHandler(EarlyRefresh earlyRefresh) {
        this.earlyRefresh = earlyRefresh;
    }

    /**
     * 语义 counter 元数据声明:同步提前过期触发事件计数。
     */
    @Override
    protected CounterMetadata semanticCounter() {
        return new CounterMetadata(
                "resicache.handler.early-refresh.triggered",
                "Early refresh triggered (sync=true early expiration path, ActualCacheHandler skipped)");
    }

    @Override
    protected boolean shouldHandle(CacheContext context) {
        // 仅 GET 操作且启用了提前过期(经稳定 CachePolicyView 读取)
        return context.getOperation() == CacheOperation.GET
               && context.policy().enableEarlyExpiration();
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        EarlyRefresh.Evaluation evaluation = earlyRefresh.evaluate(context);
        if (evaluation == null) {
            // 缓存不存在或已过期:不预取,后续节点走原生 GET 路径
            // (prefetchDecision 保持 null)
            return HandlerResult.continueChain();
        }

        EarlyExpirationDecision decision = evaluation.decision();
        boolean skipped = decision.needsRefresh() && decision.isSync();
        // 一次性写入类型化 PrefetchDecision:命中值供 ActualCacheHandler 复用(不做二次 GET)
        context.setPrefetchDecision(PrefetchDecision.of(skipped, evaluation.cachedValue(), decision));

        if (!skipped) {
            // 不需要刷新或异步刷新,继续执行
            return HandlerResult.continueChain();
        }

        // 同步提前过期:返回 skipAll，ActualCacheHandler 检查 prefetchDecision 后返回 miss
        log.debug("Sync early-expiration triggered, skipping actual cache: cacheName={}, key={}",
                  context.getCacheName(), context.getRedisKey());
        // 同步提前过期触发事件计数
        safeIncrementSemantic();
        return HandlerResult.skipAll();
    }
}
