package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 责任链管理器 — ADR-0009 抽 Engine 后的 thin facade；ADR-0022 起为链结构单一真理源。
 *
 * <p>原先在 {@code execute} 中的链推进 + 节点级决策分发 + Timer 装配 / 记录 +
 * MDC stamp（约 110 SLOC）已全部迁出到 {@link ChainEngine}。本 facade 只保留：
 *
 * <ul>
 *   <li>{@code List<CacheHandler> handlers} 维护（addHandler / size / clear / getHandlerNames）—
 *       <b>ADR-0022</b> 起为链结构唯一表示，不再并行维护 next 指针链 / head 引用</li>
 *   <li>{@code synchronized(chainGuard)} 守护 handlers 结构性修改(addHandler/clear 与
 *       size/getHandlerNames 互斥);<b>execute() 无锁</b> —— 委派 Engine,后者用
 *       {@link ChainEngine#chainSnapshotRef AtomicReference} 不可变快照({@code List.copyOf})
 *       提供完整并发隔离。原 facade {@code ReadWriteLock} 是 ADR-0022 删 next 指针前遗留的
 *       双轨冗余(读写锁与 AtomicReference 守护同一份语义),现收敛为单一真理源(snapshot),
 *       execute 零锁开销</li>
 *   <li>{@link #execute(CacheContext)} 委派给 {@link ChainEngine#execute(CacheContext)}</li>
 *   <li>{@link #MDC_REQUEST_ID_KEY} 常量（供 {@code MDCStampChainObserver} 引用）</li>
 * </ul>
 *
 * <p>执行流程：facade 在 {@link #addHandler(CacheHandler)} / {@link #clear()} 时
 * 同步刷新 {@link ChainEngine#setChainSnapshot(List)}，让 Engine 拿到最新链快照；
 * {@link #execute(CacheContext)} 直接委派 Engine,无任何锁。
 *
 * <p><b>back-compat 兜底</b>：保留无参构造（{@code @Autowired} 注入 Engine），
 * 用户若需自定义 ChainEngine（如额外加 observer），声明
 * {@code @Bean @ConditionalOnMissingBean ChainEngine} 顶替默认即可。
 *
 * <p><b>facade 存在代价（删除测试）</b>：删掉本类 → 用户需在
 * {@code RedisProCacheWriter} 与测试中直接持 {@link ChainEngine} 引用，复杂度
 * 重现且失去"facade 维护链结构 / engine 推进链"职责分层。本 facade 挣得起存在代价。
 */
@Slf4j
@Component
public class CacheHandlerChain {

    /**
     * MDC key 用于 stamp 每次链执行的关联 id。
     * <p>本常量被 {@code MDCStampChainObserver}（在
     * {@code onChainStart}/{@code onChainEnd}）和
     * {@code ChainDebugLogChainObserver}（在 {@code afterNode} 读 MDC）共同引用。
     * 重命名此 key 需同步改两处 observer。
     */
    public static final String MDC_REQUEST_ID_KEY = "requestId";

    /** 所有处理器列表（用于调试和后置处理；ADR-0022 起为链结构单一真理源） */
    private final List<CacheHandler> handlers = new ArrayList<>();

    /**
     * 守护 handlers 列表结构性修改的内部锁监视器。
     * <p>仅 {@link #addHandler}/{@link #clear}/{@link #size}/{@link #getHandlerNames} 持有;
     * {@link #execute} <b>不</b>持锁(Engine AtomicReference 快照已隔离)。
     */
    private final Object chainGuard = new Object();

    /** 推进引擎 — 由 Spring 注入（{@code @Autowired} 字段注入避免构造重排耦合）。 */
    @Autowired
    private ChainEngine engine;

    public CacheHandlerChain() {
        // engine 字段由 Spring 注入；测试可直接 new ChainEngine() 后 setter 注入
    }

    /**
     * 测试 / 自定义装配用 setter。运行期由 Spring 通过 {@code @Autowired} 注入。
     *
     * @param engine 推进引擎（不为 null）
     */
    void setEngine(ChainEngine engine) {
        this.engine = engine;
    }

    /**
     * 添加处理器到责任链末尾 — O(N) 链表遍历。
     *
     * <p>每次 addHandler 后同步刷新 {@link ChainEngine#setChainSnapshot(List)}，
     * Engine 在 {@code execute} 时读取该快照。
     *
     * @param handler 处理器
     * @return 当前 facade（支持链式调用）
     */
    public CacheHandlerChain addHandler(CacheHandler handler) {
        synchronized (chainGuard) {
            handlers.add(handler);
            // 同步刷新 Engine 持有的快照引用 — Engine.execute 直接读 snapshot 避免并发改链
            engine.setChainSnapshot(List.copyOf(handlers));
            log.debug("Added handler to chain: {}", handler.getClass().getSimpleName());
            return this;
        }
    }

    /**
     * 执行责任链 — 委派给 {@link ChainEngine#execute(CacheContext)}（无锁）。
     *
     * <p>本 facade 不持任何锁:Engine 内部用 {@link ChainEngine#chainSnapshotRef AtomicReference}
     * 发布不可变快照({@code List.copyOf}),{@code execute} 读快照与 {@code addHandler}/
     * {@code clear} 改链完全隔离(ADR-0022)。原 facade 读锁是删 next 指针前遗留的双轨冗余,
     * 现收敛为单一真理源(snapshot),execute 零锁开销。
     *
     * @param context 缓存上下文
     * @return 处理结果
     */
    public CacheResult execute(CacheContext context) {
        // 委派 Engine — 空链保护 / aroundChain/perNode 观测 / post-process 全在 Engine
        return engine.execute(context);
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
     * 清空责任链 — 同步刷新 Engine 快照为 null。
     */
    public void clear() {
        synchronized (chainGuard) {
            handlers.clear();
            // 同步 Engine 持有的快照引用 — Engine 见到 null/empty 时直接返回 success
            engine.setChainSnapshot(null);
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
            return handlers.stream().map(h -> h.getClass().getSimpleName()).toList();
        }
    }
}
