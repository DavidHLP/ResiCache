package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 责任链管理器 — thin facade,链结构单一真理源。
 *
 * <p>链推进 + 节点级决策分发 + Timer 装配 / 记录 + MDC stamp 由 {@link ChainEngine}
 * 承担;本 facade 只保留：
 *
 * <ul>
 *   <li>{@code List<CacheHandler> handlers} 维护（addHandler / size / clear / getHandlerNames）—
 *       链结构唯一表示</li>
 *   <li>{@code synchronized(chainGuard)} 守护 handlers 结构性修改(addHandler/clear 与
 *       size/getHandlerNames 互斥);<b>execute() 无锁</b> —— 取本 facade 持有的 list
 *       不可变快照({@code List.copyOf})后委派 {@link ChainEngine#execute(List, CacheContext)},
 *       提供完整并发隔离</li>
 *   <li>{@link #execute(CacheContext)} 委派给 {@link ChainEngine#execute(List, CacheContext)}</li>
 *   <li>{@link #MDC_REQUEST_ID_KEY} 常量（供 {@code MDCStampChainObserver} 引用）</li>
 * </ul>
 *
 * <p><b>快照归属</b>:链 list 单一真理源收敛在本 facade 上;Engine 通过
 * {@link ChainEngine#execute(List, CacheContext)} 接收快照参数,并把「当前节点之后」的
 * 子链以 {@code ChainContinuation} 形态交给正在执行的 handler。
 *
 * <p><b>装配</b>:Engine 经构造期注入(本类是内部装配件,由
 * {@link CacheHandlerChainFactory} 在 {@code createChain()} 中构造,不是独立 Spring bean)。
 *
 * <p><b>facade 存在代价（删除测试）</b>：删掉本类 → 用户需在
 * {@code RedisProCacheWriter} 与测试中直接持 {@link ChainEngine} 引用，复杂度
 * 重现且失去"facade 维护链结构 / engine 推进链"职责分层。本 facade 挣得起存在代价。
 */
@Slf4j
class CacheHandlerChain {

    /**
     * MDC key 用于 stamp 每次链执行的关联 id。
     * <p>本常量被 {@code MDCStampChainObserver}（在
     * {@code onChainStart}/{@code onChainEnd}）和
     * {@code ChainDebugLogChainObserver}（在 {@code afterNode} 读 MDC）共同引用。
     * 重命名此 key 需同步改两处 observer。
     */
    public static final String MDC_REQUEST_ID_KEY = "requestId";

    /** 所有处理器列表（用于调试和后置处理；链结构单一真理源） */
    private final List<CacheHandler> handlers = new ArrayList<>();

    /**
     * 守护 handlers 列表结构性修改的内部锁监视器。
     * <p>仅 {@link #addHandler}/{@link #clear}/{@link #size}/{@link #getHandlerNames} 持有;
     * {@link #execute} <b>不</b>持锁(本 facade 在 synchronized 块内一次性拍快照)。
     */
    private final Object chainGuard = new Object();

    /** 推进引擎 — 构造期注入（本类由工厂构造，非 Spring bean）。 */
    private final ChainEngine engine;

    CacheHandlerChain(ChainEngine engine) {
        this.engine = engine;
    }

    /** Shared runtime label for handler logs and bounded observer tags. */
    static String handlerTag(CacheHandler handler) {
        return HandlerIdentity.of(handler).tag();
    }

    /**
     * 添加处理器到责任链末尾 — O(N) 链表遍历。
     *
     * <p>Engine 不持有快照引用 —— 下次 {@link #execute} 会从本 facade 的 handlers
     * 列表重新拍快照。
     *
     * @param handler 处理器
     * @return 当前 facade（支持链式调用）
     */
    public CacheHandlerChain addHandler(CacheHandler handler) {
        synchronized (chainGuard) {
            handlers.add(handler);
            log.debug("Added handler to chain: {}", handlerTag(handler));
            return this;
        }
    }

    /**
     * 执行责任链 — 委派给 {@link ChainEngine#execute(List, CacheContext)}（无锁）。
     *
     * <p>本 facade 在 synchronized 块内一次性拍 {@code List.copyOf(handlers)} 快照,
     * 快照交给 Engine;Engine 完全不持 list 状态,节点嵌套推进以
     * {@code ChainContinuation} 形态交回 handler(见 {@code CacheHandler})。
     *
     * @param context 缓存上下文
     * @return 处理结果
     */
    public CacheResult execute(CacheContext context) {
        List<CacheHandler> snapshot;
        synchronized (chainGuard) {
            snapshot = List.copyOf(handlers);
        }
        return engine.execute(snapshot, context);
    }

    /**
     * 获取处理器数量。
     *
     * @return 处理器数量
     */
    public int size() {
        synchronized (chainGuard) {
            return handlers.size();
        }
    }

    /**
     * 清空责任链 — Engine 完全不持 list 状态,本 facade 的 handlers 列表空 →
     * 下次 execute 拍出空快照 → Engine 直接走空链路径。
     */
    public void clear() {
        synchronized (chainGuard) {
            handlers.clear();
            log.debug("Handler chain cleared");
        }
    }

    /**
     * 获取所有处理器名称。
     *
     * @return 处理器名称列表
     */
    public List<String> getHandlerNames() {
        synchronized (chainGuard) {
            return handlers.stream().map(CacheHandlerChain::handlerTag).toList();
        }
    }
}
