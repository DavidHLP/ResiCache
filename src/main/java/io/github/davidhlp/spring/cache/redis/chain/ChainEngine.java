package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncLockHandler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 责任链推进引擎 — ADR-0009 (Chain Engine extraction) D1.
 *
 * <p>把"链推进 + 节点级决策分发 + 观测编排 + post-process 遍历"四件关注点
 * 集中到单一 {@code @Component} seam，替换原先散落在
 * {@link AbstractCacheHandler#handle(CacheContext)} 与
 * {@link CacheHandlerChain#execute(CacheContext)} 的并行实现（约 600 SLOC
 * 中 ~120 SLOC 是引擎样板）。
 *
 * <p><b>推进协议</b>：Engine 持有有序的 {@link CacheHandler} 列表（snapshot
 * 形式，对应原 CacheHandlerChain 的 {@code List<CacheHandler> handlers}），
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
 * <p><b>Post-process</b>：链主路径完成后，Engine 遍历所有 handler，对实现
 * {@link PostProcessHandler} 的 handler 调用其
 * {@link PostProcessHandler#afterChainExecution(CacheContext, CacheResult)} —
 * 替换原 {@code CacheHandlerChain.executePostProcess} 私有方法。失败 try/catch
 * 不污染主链（与原行为一致）。
 *
 * <p><b>executeFragment</b>：{@link SyncLockHandler} 锁内推进用，跳过
 * aroundChain 观测（避免重复 stamp MDC / 重复 record Timer）+ 不做
 * post-process（外层 {@link #execute} 完成）。仅做节点推进 + perNode 观测。
 *
 * <p><b>线程安全</b>：Engine 单例 Bean，{@link #observers} 字段为
 * {@link ObserverRegistry}（内部 {@code CopyOnWriteArrayList}，启动期单写、热期
 * 多读），observer 自身必须线程安全。Handler 列表由 {@link CacheHandlerChain}
 * 持锁读快照传入，Engine 内部不修改该列表。
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

    /** 工厂建链后注入（{@link CacheHandlerChainFactory#createChain}）。 */
    private final AtomicReference<List<CacheHandler>> chainSnapshotRef = new AtomicReference<>();

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
     * 设置当前生效的 handler 链快照（由 {@link CacheHandlerChain} 在 addHandler /
     * clear 时调用）。Engine 每次 {@link #execute} 读取该快照遍历，
     * 避免 {@link CacheHandlerChain} 修改链表时被 Engine 边遍历边改。
     *
     * @param snapshot 当前 handler 链快照（可能为 null 表示空链）
     */
    public void setChainSnapshot(List<CacheHandler> snapshot) {
        chainSnapshotRef.set(snapshot);
    }

    /**
     * 执行责任链 — 整条 chain 全生命周期（head handle + post-process + 观测）。
     *
     * <p>执行流程：
     * <ol>
     *   <li>快照当前 handler 链；空链返回 success 并打 WARN</li>
     *   <li>所有 observer.onChainStart</li>
     *   <li>节点循环：beforeNode → handler.handle → afterNode → decision switch</li>
     *   <li>所有 observer.onChainEnd（即使主路径异常也调用）</li>
     *   <li>post-process 遍历</li>
     * </ol>
     *
     * @param context 缓存上下文
     * @return 链执行最终结果（post-process 已执行）
     */
    public CacheResult execute(CacheContext context) {
        List<CacheHandler> snapshot = chainSnapshotRef.get();
        if (snapshot == null || snapshot.isEmpty()) {
            log.warn("Handler chain is empty!");
            // 仍然走 onChainStart/onChainEnd 配对 — observer 可能在 start 注册
            // thread-local 资源（如 Timer.Sample），不配对会泄漏
            observers.forEach(o -> o.onChainStart(context));
            try {
                return CacheResult.success();
            } finally {
                observers.forEach(o -> o.onChainEnd(context, CacheResult.success()));
            }
        }

        log.debug("Executing handler chain for operation: {}, cacheName: {}, key: {}",
                context.getOperation(), context.getCacheName(), context.getRedisKey());

        // aroundChain 观测：start
        observers.forEach(o -> o.onChainStart(context));

        CacheResult finalResult;
        try {
            // 节点推进主循环
            finalResult = driveChain(snapshot, context);

            // post-process 遍历（在 onChainEnd 之前，与原 CacheHandlerChain.execute 顺序一致 —
            // 任何 observer 依赖"链已完全结束"语义时应看到 post-process 副作用）
            executePostProcess(snapshot, context, finalResult);
        } finally {
            // aroundChain 观测：end（即使主路径异常也调用，保证 observer 资源配对）
            observers.forEach(o -> o.onChainEnd(context, CacheResult.success()));
        }

        return finalResult;
    }

    /**
     * 在锁内 / 嵌套场景推进剩余链 — 跳过 aroundChain 观测与 post-process。
     *
     * <p>典型调用方：{@code SyncLockHandler.executeChainInLock} 在分布式锁
     * 持有期间推进 getNext() 到链尾。锁外层 {@link #execute} 已 stamp MDC /
     * 启动 Timer，锁内不能再 stamp（避免覆盖）或重复 record（重复打点）。Post-process
     * 由外层 execute 在锁返回后统一调用，锁内片段无需重复。
     *
     * <p>行为：仅 perNode 观测（beforeNode / afterNode），aroundChain 观测忽略。
     * 当前实现等于去掉 aroundChain 调用的 driveChain 子集。
     *
     * @param context    缓存上下文（与外层 execute 共享）
     * @param from       推进起点 handler（不含）；为 null 时直接返回 success
     * @return 从 {@code from} 推进到链尾的最终结果（无 post-process）
     */
    public CacheResult executeChainFragment(CacheContext context, CacheHandler from) {
        if (from == null) {
            return CacheResult.success();
        }
        // 构造 from 起点的子列表 — driveChain 用 List<CacheHandler> 遍历，
        // 子列表保持顺序且仅含 from 及其后继
        List<CacheHandler> fragment = buildFragment(from);
        if (fragment.isEmpty()) {
            return CacheResult.success();
        }
        // 复用 driveChain：aroundChain 观测由调用方外层 execute 负责，
        // 本方法只跑 perNode（beforeNode / afterNode）
        return driveChain(fragment, context);
    }

    /**
     * 从 {@code from} 沿 {@link CacheHandler#getNext()} 链构造有序子列表。
     */
    private static List<CacheHandler> buildFragment(CacheHandler from) {
        java.util.ArrayList<CacheHandler> out = new java.util.ArrayList<>();
        CacheHandler cur = from;
        // 防御性环检测 — 走 N 次未到 null 视为坏链，截断
        int guard = 0;
        while (cur != null && guard++ < 1024) {
            out.add(cur);
            cur = cur.getNext();
        }
        return out;
    }

    /**
     * 节点推进主循环 — 抽取出来供 {@link #execute} 与 {@link #executeChainFragment}
     * 共享。每次循环：
     * <ol>
     *   <li>检测 context.isSkipRemaining() — 短路返回当前 result（等价为继续但带 success）</li>
     *   <li>observer.beforeNode</li>
     *   <li>handler.handle(ctx)</li>
     *   <li>observer.afterNode</li>
     *   <li>decision switch（CONTINUE 推进 / SKIP_ALL 物化 / TERMINATE 终止）</li>
     * </ol>
     */
    private CacheResult driveChain(List<CacheHandler> snapshot, CacheContext context) {
        CacheHandler current = snapshot.get(0);
        int idx = 0;
        while (current != null) {
            // 上游 SKIP_ALL 已物化：短路返回 success（与原 AbstractCacheHandler.handle 一致）
            if (context.isSkipRemaining()) {
                return CacheResult.success();
            }

            HandlerResult result = invokeWithObservers(current, context);

            switch (result.decision()) {
                case CONTINUE:
                    if (current.getNext() != null) {
                        current = current.getNext();
                        idx++;
                    } else {
                        // 链尾 CONTINUE：返回 handler 的 result（result 为 null 时退化为 success —
                        // 与原 executeChainInternal 行为一致："返回的 HandlerResult.result() 为 null
                        // 时退化为 CacheResult.success()"）
                        return result.result() != null ? result.result() : CacheResult.success();
                    }
                    break;
                case SKIP_ALL:
                    context.markSkipRemaining();
                    return result.result() != null ? result.result() : CacheResult.success();
                case TERMINATE:
                    return result.result() != null ? result.result() : CacheResult.success();
                default:
                    throw new IllegalStateException("Unknown ChainDecision: " + result.decision());
            }
        }
        // 防御：理论不会到这里（CONTINUE 分支已处理链尾）
        return CacheResult.success();
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
        observers.forEach(o -> o.beforeNode(handler, context));
        HandlerResult result = handler.handle(context);
        observers.forEach(o -> o.afterNode(handler, context, result));
        return result;
    }

    /**
     * Post-process 遍历 — 替换原 {@code CacheHandlerChain.executePostProcess}。
     * 失败 try/catch 不污染主链（与原行为一致），打 ERROR 日志。
     */
    private void executePostProcess(List<CacheHandler> handlers, CacheContext context, CacheResult result) {
        for (CacheHandler handler : handlers) {
            if (handler instanceof PostProcessHandler postHandler) {
                if (postHandler.requiresPostProcess(context)) {
                    try {
                        postHandler.afterChainExecution(context, result);
                        log.debug("Post-processing executed for: {}",
                                handler.getClass().getSimpleName());
                    } catch (Exception e) {
                        log.error("Post-processing failed for: {}, operation: {}, key: {}",
                                handler.getClass().getSimpleName(),
                                context.getOperation(),
                                context.getRedisKey(), e);
                        // 后置处理失败不影响主链结果
                    }
                }
            }
        }
    }
}
