package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncLockHandler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 责任链推进引擎 — ADR-0009 (Chain Engine extraction) D1.
 *
 * <p>把"链推进 + 节点级决策分发 + 观测编排 + post-process 遍历"四件关注点
 * 集中到单一 {@code @Component} seam，替换原先散落在
 * {@link AbstractCacheHandler#handle(CacheContext)} 与
 * {@link CacheHandlerChain#execute(CacheContext)} 的并行实现（约 600 SLOC
 * 中 ~120 SLOC 是引擎样板）。
 *
 * <p><b>推进协议</b>：Engine 接收有序的 {@link CacheHandler} 快照（由
 * {@link CacheHandlerChain#execute(CacheContext)} 在 synchronized 块内一次性拍出），
 * 按顺序调用每个 handler 的 {@code handle(ctx)}；handler 返回的
 * {@link HandlerResult#decision()} 决定走向：
 *
 * <ul>
 *   <li>{@link ChainDecision#CONTINUE} — 推进到下一个 handler；无下一个则返回当前 result</li>
 *   <li>{@link ChainDecision#SKIP_ALL} — 物化 {@code context.markSkipRemaining()}，
 *       返回 result，下游 handler 短路（由 beforeNode 检测 skipRemaining 状态）</li>
 *   <li>{@link ChainDecision#TERMINATE} — 直接返回 result</li>
 * </ul>
 *
 * <p><b>观测编排</b>：Engine 在链入口调用所有 observer 的
 * {@link ChainObserver#onChainStart(CacheContext)}，节点前后调用
 * {@link ChainObserver#beforeNode}/{@link ChainObserver#afterNode}，
 * 链出口调用 {@link ChainObserver#onChainEnd(CacheContext, CacheResult)}。
 * Observer 实现以 default no-op 形式提供（见 {@link ChainObserver}），
 * Engine 自身不感知 MDC / Timer / Counter / DEBUG log 等具体关注点 —
 * 这是 WS-1.4 Observation Span 升级路径的核心 leverage。
 *
 * <p><b>Post-process</b>：链主路径完成后，Engine 遍历所有 handler，对
 * {@link CacheHandler#requiresPostProcess(CacheContext)} 返回 {@code true}
 * 的 handler 调用其 {@link CacheHandler#afterChainExecution(CacheContext, CacheResult)}
 * — 替换原 {@code CacheHandlerChain.executePostProcess} 私有方法。失败 try/catch
 * 不污染主链（与原行为一致）。<b>ADR-0045</b> 替代了原 {@code instanceof
 * PostProcessHandler} 分支,opt-in 语义改走类型化的 requiresPostProcess hook,
 * 消灭了 seam 边界 type check。
 *
 * <p><b>executeFragment</b>：{@link SyncLockHandler} 锁内推进用，跳过
 * aroundChain 观测（避免重复 stamp MDC / 重复 record Timer）+ 不做
 * post-process（外层 {@link #execute} 完成）。仅做节点推进 + perNode 观测。
 *
 * <p><b>线程安全</b>：Engine 单例 Bean，{@link #observers} 字段为
 * {@link ObserverRegistry}（内部 {@code CopyOnWriteArrayList}，启动期单写、热期
 * 多读），observer 自身必须线程安全。Handler 列表由 {@link CacheHandlerChain}
 * 完全持有;Engine 内部不修改该列表。
 *
 * <p><b>ADR-0046</b>:Engine 上的 {@code chainSnapshotRef} + {@code setChainSnapshot}
 * 已删除 — 链 list 单一真理源完全收敛在 {@code CacheHandlerChain},Engine 通过
 * {@link #execute(List, CacheContext)} 接收快照参数,并用 ThreadLocal
 * ({@link #CURRENT_SNAPSHOT})在 execute entry 设入 / finally 清出,供
 * {@link #executeChainFragment(CacheContext, CacheHandler)} 隐式读取。Thread-local
 * 取代全局 AtomicReference,per-thread 隔离更强(并发 execute 互不污染)。
 *
 * <p><b>Observer 列表管理委派</b>(ADR-0016):{@code addObserver} / {@code observers}
 * / 遍历逻辑委派到 {@link ObserverRegistry} 单一 seam,与
 * {@code handler.AnnotationChainEngine} 共用 — 消除两 engine 间 ~30 SLOC 的
 * observer 列表样板重复。
 */
@Slf4j
@Component
public class ChainEngine {

    /** 注册的 observer 列表 — 委派到 {@link ObserverRegistry}(ADR-0016 单一 seam). */
    private final ObserverRegistry<ChainObserver> observers = new ObserverRegistry<>();

    /**
     * 当前线程正在执行的 handler 链快照(ADR-0046):由 {@link #execute(List, CacheContext)}
     * entry 处 set,finally 块 remove;供 {@link #executeChainFragment(CacheContext, CacheHandler)}
     * 在同线程隐式读取(SyncLockHandler 锁内推进)。
     *
     * <p>取代了原 {@code AtomicReference chainSnapshotRef} 全局字段 — ThreadLocal
     * 提供 per-thread 隔离,并发 execute 互不污染。
     */
    private static final ThreadLocal<List<CacheHandler>> CURRENT_SNAPSHOT = new ThreadLocal<>();

    public ChainEngine() {
        // observers 由外部 addObserver(...) 注入；ChainHandlerChainFactory 在装配时调用
    }

    /**
     * 注册一个 observer。重复注册同名 observer 由调用方负责去重（Engine 不强制
     * 唯一性，避免反射 / class 名比较的反射开销）。注册时机：Engine 创建后、
     * 首次 execute 前。
     *
     * @param observer 待注册的 observer（不为 null）
     * @throws IllegalArgumentException 若 observer 为 null
     */
    public void addObserver(ChainObserver observer) {
        observers.add(observer);
    }

    /**
     * 暴露当前已注册的 observer 列表（只读快照）。测试与诊断用；运行期勿修改。
     *
     * @return 不可变 observer 列表快照
     */
    public List<ChainObserver> observers() {
        return observers.snapshot();
    }

    /**
     * <b>仅供测试使用</b>(package-private):直接设入 {@link #CURRENT_SNAPSHOT},
     * 模拟 {@link #execute(List, CacheContext)} 已为当前线程准备好快照的状态。
     * <p>测试场景:验证 {@link #executeChainFragment(CacheContext, CacheHandler)}
     * 在快照就绪时的行为,而无需走完整 execute 流程(后者会触发 observer 钩子)。
     * <p>生产代码请用 {@link #execute(List, CacheContext)} — 它会正确管理
     * ThreadLocal 的 set / remove 配对。
     */
    void setCurrentSnapshotForTest(List<CacheHandler> snapshot) {
        CURRENT_SNAPSHOT.set(snapshot);
    }

    /**
     * <b>仅供测试使用</b>(package-private):清空 {@link #CURRENT_SNAPSHOT}。
     */
    void clearCurrentSnapshotForTest() {
        CURRENT_SNAPSHOT.remove();
    }

    /**
     * 执行责任链 — 整条 chain 全生命周期(head handle + post-process + 观测)。
     *
     * <p><b>ADR-0046</b>:接收 {@code snapshot} 作为参数(由 {@link CacheHandlerChain}
     * 在 synchronized 块内拍出),Engine 不再持有 list 状态;ThreadLocal 在 entry
     * 处 set,finally 块 remove,供 {@code executeChainFragment} 隐式读。
     *
     * <p><b>ADR-0056 收敛</b>(Round 42):本方法在 Round 42 之后只剩「ThreadLocal + 空链
     * 告警 + 委派」3 步。around-hook 配对 + post-process + 异常守护已迁出至
     * {@link ChainLifecycle} 私有内嵌 seam,Engine 自身的 try/finally 减少 1 层,
     * 不再内联 onChainStart / onChainEnd / post-process 循环的 4 个 observers.forEachSafe
     * 调用点。
     *
     * <p>执行流程：
     * <ol>
     *   <li>快照当前 handler 链；空链打 WARN(由 ChainLifecycle 仍跑 around-hook 配对)</li>
     *   <li>所有 observer.onChainStart — ChainLifecycle 入口</li>
     *   <li>节点循环:beforeNode → handler.handle → afterNode → decision switch — driveChain</li>
     *   <li>post-process 遍历 — ChainLifecycle 内部</li>
     *   <li>所有 observer.onChainEnd(即使主路径异常也调用) — ChainLifecycle finally 守护</li>
     * </ol>
     *
     * @param snapshot handler 链快照({@link CacheHandlerChain} 一次性 {@code List.copyOf} 产出)
     * @param context  缓存上下文
     * @return 链执行最终结果(post-process 已执行)
     */
    public CacheResult execute(List<CacheHandler> snapshot, CacheContext context) {
        CURRENT_SNAPSHOT.set(snapshot);
        try {
            if (snapshot == null || snapshot.isEmpty()) {
                log.warn("Handler chain is empty!");
            }
            log.debug("Executing handler chain for operation: {}, cacheName: {}, key: {}",
                    context.getOperation(), context.getCacheName(), context.getRedisKey());
            return new ChainLifecycle(observers, snapshot, context).run();
        } finally {
            CURRENT_SNAPSHOT.remove();
        }
    }

    /**
     * 在锁内 / 嵌套场景推进 {@code from} <b>之后</b>的剩余链 — 跳过 aroundChain 观测与 post-process。
     *
     * <p>典型调用方：{@code SyncLockHandler} 在分布式锁持有期间推进"自己之后"的剩余
     * handler。锁外层 {@link #execute} 已 stamp MDC / 启动 Timer，锁内不能再 stamp
     * （避免覆盖）或重复 record（重复打点）。Post-process 由外层 execute 在锁返回后
     * 统一调用，锁内片段无需重复。
     *
     * <p><b>ADR-0022</b>：定位起点改为基于 snapshot {@code indexOf(from) + 1}（不再沿
     * {@code getNext()} 指针链构造子列表）。{@code from} 通常是发起片段推进的 handler
     * 自身（如 {@code SyncLockHandler} 传 {@code this}），Engine 推进其后的所有 handler。
     *
     * <p>行为：仅 perNode 观测（beforeNode / afterNode），aroundChain 观测忽略。
     *
     * @param context 缓存上下文（与外层 execute 共享）
     * @param from    推进起点的边界 handler（推进其<b>后继</b>；为 null 或不在快照中时返回 success）
     * @return 从 {@code from} 之后推进到链尾的最终结果（无 post-process）
     */
    public CacheResult executeChainFragment(CacheContext context, CacheHandler from) {
        if (from == null) {
            return CacheResult.success();
        }
        List<CacheHandler> snapshot = CURRENT_SNAPSHOT.get();
        if (snapshot == null || snapshot.isEmpty()) {
            return CacheResult.success();
        }
        int start = snapshot.indexOf(from);
        // from 不在快照中（理论不应发生）或已是链尾 → 无后继可推进
        if (start < 0 || start + 1 >= snapshot.size()) {
            return CacheResult.success();
        }
        // 不可变快照的 subList view — driveChain 只读（get / size），view 安全；
        // 复用 driveChain：aroundChain 观测由调用方外层 execute 负责，本方法只跑 perNode
        return driveChain(snapshot.subList(start + 1, snapshot.size()), context);
    }

    /**
     * 节点推进主循环 — 抽取出来供 {@link #execute} 与 {@link #executeChainFragment}
     * 共享。按 snapshot index 顺序推进（<b>ADR-0022</b>：不再沿 {@code getNext()} 指针）：
     * <ol>
     *   <li>检测 context.isSkipRemaining() — 短路返回 success</li>
     *   <li>observer.beforeNode</li>
     *   <li>handler.handle(ctx)</li>
     *   <li>observer.afterNode</li>
     *   <li>decision switch（CONTINUE 推进下一 index / SKIP_ALL 物化 / TERMINATE 终止）</li>
     * </ol>
     *
     * <p><b>并发隔离（ADR-0022 修复）</b>：snapshot 由 {@link #setChainSnapshot} 注入的
     * 不可变 {@code List.copyOf} 产出，index 推进完全在快照内读取。此前沿 {@code getNext()}
     * 读 handler 实例字段，不受快照隔离保护 —— 改 index 推进后，{@code addHandler} 改链
     * 仅影响下次 {@code setChainSnapshot}，当前 {@code execute} 持有的快照引用完全隔离。
     *
     * @param snapshot 不可变 handler 链快照（Engine 只读，不修改）
     */
    private CacheResult driveChain(List<CacheHandler> snapshot, CacheContext context) {
        for (int idx = 0; idx < snapshot.size(); idx++) {
            // 上游 SKIP_ALL 已物化：短路返回 success（与原 AbstractCacheHandler.handle 一致）
            if (context.isSkipRemaining()) {
                return CacheResult.success();
            }
            CacheHandler current = snapshot.get(idx);
            HandlerResult result = invokeWithObservers(current, context);

            switch (result.decision()) {
                case CONTINUE:
                    // 链尾 CONTINUE：返回 handler 的 result（result 为 null 时退化为 success —
                    // 与原 executeChainInternal 行为一致："返回的 HandlerResult.result() 为 null
                    // 时退化为 CacheResult.success()"）
                    if (idx == snapshot.size() - 1) {
                        return materialize(result);
                    }
                    // 非链尾：idx++ 推进到下一 handler
                    break;
                case SKIP_ALL:
                    context.markSkipRemaining();
                    return materialize(result);
                case TERMINATE:
                    return materialize(result);
                default:
                    throw new IllegalStateException("Unknown ChainDecision: " + result.decision());
            }
        }
        // 空快照（理论由 execute 前置拦截，防御）
        return CacheResult.success();
    }

    /**
     * 把 {@link HandlerResult} 物化为 {@link CacheResult} —— null 退化为 success
     * 的单一权威 helper。原 driveChain 在三个 decision 分支各写一份
     * {@code result != null ? result : success()},加新 decision 时易漏；本 helper
     * 收敛后三处走同一行委派,deletion test 保护语义。
     */
    private static CacheResult materialize(HandlerResult result) {
        return result.result() != null ? result.result() : CacheResult.success();
    }

    /**
     * 单节点调用：beforeNode → handler.handle(ctx) → afterNode。Engine 不捕获
     * handler 异常（异常冒泡由调用方决定 — 当前 execute 的 try/finally 只守护
     * onChainEnd 配对，handler 异常直接冒泡给 CacheHandlerChain.execute 的
     * 调用方，与原 AbstractCacheHandler 行为一致：异常即不计 perNode counter / log）。
     *
     * <p>结果传递：handler.handle 返回后调用 afterNode(handler, ctx, result)，
     * 让 observer（如 ChainDebugLogChainObserver / FiredCounterChainObserver）
     * 能拿到 result.decision() 做后续处理。
     */
    private HandlerResult invokeWithObservers(CacheHandler handler, CacheContext context) {
        observers.forEachSafe(o -> o.beforeNode(handler, context));
        HandlerResult result = handler.handle(context);
        observers.forEachSafe(o -> o.afterNode(handler, context, result));
        return result;
    }

    // ==================== ChainLifecycle (ADR-0056 / Round 42 seam) ====================

    /**
     * 责任链全生命周期守护 — ADR-0056 / Round 42 抽出的私有 seam.
     *
     * <p>封装 ChainEngine.execute 此前 4 件交织的关注点(ADR-0056 report 候选 2):
     * <ol>
     *   <li><b>around-hook 配对</b>:onChainStart → driveChain + post-process → onChainEnd
     *       (即使主路径异常也调用 onChainEnd,保证 observer 资源配对 — 防止 MDC / Timer
     *       跨 execute 调用的资源泄漏)</li>
     *   <li><b>post-process 遍历</b>:对所有 {@code requiresPostProcess} opt-in 的
     *       handler 调用 {@code afterChainExecution},失败 try/catch 隔离不污染主链</li>
     *   <li><b>异常守护</b>:driveChain 抛出的异常继续向上冒泡(与原行为一致),
     *       onChainEnd 仍由 finally 触发</li>
     *   <li><b>空链短路</b>:snapshot 为空时仍配对 around-hook(observer 可能在 start
     *       注册 thread-local 资源如 Timer.Sample,不配对会泄漏),但跳过 driveChain
     *       + post-process</li>
     * </ol>
     *
     * <p><b>ADR-0061 scope token 配对</b>(Round 46):onChainStart 收集每个 observer
     * 返回的 scope token,onChainEnd 按相同 observer 顺序回传(逐个 observer 配对,
     * 跨 observer 不混淆)。Engine 不感知 token 内部协议 —— observer 状态机完全
     * 自承,CacheContext 不再承担 stringly-typed 通用 attributes 袋。
     *
     * <p><b>设计纪律</b>:
     * <ul>
     *   <li>private final 嵌套类(非 static)— 不暴露给外部(只服务 ChainEngine.execute
     *       一处);非 static 因需调外部 instance method {@code driveChain},持 outer
     *       reference 是 locality 提升而非泄漏</li>
     *   <li>不动 onChainEnd 传入 {@code CacheResult.success()} 硬编码(原行为,见 ADR-0056
     *       「设计纪律」一节解释)</li>
     *   <li>run() 无参(不返回 mainResult 后再由 caller 收 mainResult),避免与 caller
     *       形成 split-knowledge</li>
     * </ul>
     *
     * <p><b>deletion test</b>:把 ChainLifecycle 删掉、内联回 execute → 47 SLOC
     * execute 回归 + 3 层 try/finally 嵌套恢复 + around-end 在 2 处独立写 2 遍,
     * 复杂度上升。本 seam 浓缩。
     */
    private final class ChainLifecycle {

        private final ObserverRegistry<ChainObserver> observers;
        private final List<CacheHandler> snapshot;
        private final CacheContext context;

        ChainLifecycle(ObserverRegistry<ChainObserver> observers,
                       List<CacheHandler> snapshot,
                       CacheContext context) {
            this.observers = observers;
            this.snapshot = snapshot;
            this.context = context;
        }

        /**
         * 执行全生命周期:around-start → driveChain + post-process → around-end.
         *
         * <p>空链(snapshot == null || isEmpty())时仍配对 around-hook,但跳过
         * driveChain + post-process,直接返回 {@link CacheResult#success()}。
         *
         * <p>driveChain 抛出的异常继续向上冒泡(与原 execute 行为一致);
         * onChainEnd 由 finally 守护保证触发。
         *
         * <p><b>ADR-0061 scope token 收集</b>:around-start 阶段逐个调 observer
         * 的 {@code onChainStart},把每个 observer 返回的 scope token 写入
         * {@code scopeTokens} 数组(下标 = observer 在 registry 快照中的 index);
         * around-end 阶段按相同 index 逐个调 {@code onChainEnd(ctx, token, result)}。
         * 配对规则:onChainStart 抛异常的 observer(token 未被收集)在 onChainEnd 时
         * 传 null(token 槽位保持初始 null),保证配对循环不越界。
         */
        CacheResult run() {
            List<ChainObserver> observerList = observers.snapshot();
            Object[] scopeTokens = new Object[observerList.size()];
            for (int i = 0; i < observerList.size(); i++) {
                ChainObserver o = observerList.get(i);
                try {
                    scopeTokens[i] = o.onChainStart(context);
                } catch (Exception ex) {
                    log.error("Observer {} onChainStart failed: {}",
                            o.getClass().getSimpleName(), ex.toString(), ex);
                    // token 留 null,onChainEnd 仍按 index 配对 — 失败 observer 收 null
                }
            }
            CacheResult mainResult = CacheResult.success();
            try {
                if (snapshot != null && !snapshot.isEmpty()) {
                    mainResult = driveChain(snapshot, context);
                    runPostProcess(mainResult);
                }
            } finally {
                // ADR-0056 保留:onChainEnd 仍传 hardcoded CacheResult.success() 而非 mainResult。
                // 原行为如此(commit 现状),observer 当前不读 result 字段,observably 字节等价。
                // 若未来 observer 需要 mainResult,需独立 round 决定。
                for (int i = 0; i < observerList.size(); i++) {
                    ChainObserver o = observerList.get(i);
                    try {
                        o.onChainEnd(context, scopeTokens[i], CacheResult.success());
                    } catch (Exception ex) {
                        log.error("Observer {} onChainEnd failed: {}",
                                o.getClass().getSimpleName(), ex.toString(), ex);
                    }
                }
            }
            return mainResult;
        }

        /**
         * post-process 遍历 — 替换原 ChainEngine.executePostProcess 私有方法.
         *
         * <p>失败 try/catch 不污染主链(与原行为一致),打 ERROR 日志。
         */
        private void runPostProcess(CacheResult mainResult) {
            for (CacheHandler handler : snapshot) {
                if (handler.requiresPostProcess(context)) {
                    try {
                        handler.afterChainExecution(context, mainResult);
                        log.debug("Post-processing executed for: {}",
                                handler.getClass().getSimpleName());
                    } catch (Exception e) {
                        log.error("Post-processing failed for: {}, operation: {}, key: {}",
                                handler.getClass().getSimpleName(),
                                context.getOperation(),
                                context.getRedisKey(), e);
                    }
                }
            }
        }
    }
}
