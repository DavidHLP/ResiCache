package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.ChainContinuation;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.observer.ChainObserver;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 责任链推进引擎 — 把"链推进 + 节点级决策分发 + 观测编排 + post-process 遍历"
 * 四件关注点集中到单一 {@code @Component} seam。
 *
 * <p><b>推进协议</b>：Engine 接收有序的 {@link CacheHandler} 快照（由
 * {@link CacheHandlerChain#execute(CacheContext)} 在 synchronized 块内一次性拍出），
 * 按顺序调用每个 handler 的 {@code handle(ctx, continuation)}；handler 返回的
 * {@link HandlerResult#decision()} 决定走向：
 *
 * <ul>
 *   <li>{@link FlowControl#CONTINUE} — 推进到下一个 handler；无下一个则返回当前 result</li>
 *   <li>{@link FlowControl#SKIP_ALL} — 物化 {@code context.markSkipRemaining()}，
 *       返回 result，下游 handler 短路（由 beforeNode 检测 skipRemaining 状态）</li>
 *   <li>{@link FlowControl#TERMINATE} — 直接返回 result</li>
 * </ul>
 *
 * <p><b>观测编排</b>：Engine 在链入口调用所有 observer 的
 * {@link ChainObserver#onChainStart(CacheContext)}，节点前后调用
 * {@link ChainObserver#beforeNode}/{@link ChainObserver#afterNode}，
 * 链出口调用 {@link ChainObserver#onChainEnd(CacheContext, Object, CacheResult)}；
 * 正常完成时传最终结果，主路径抛异常时传 {@code null}（表示未产生结果）。
 * Observer 实现以 default no-op 形式提供（见 {@link ChainObserver}），
 * Engine 自身不感知 MDC / Timer / Counter / DEBUG log 等具体关注点 —
 * 新增观测维度只需新增 observer,Engine / handler 零修改。
 *
 * <p><b>Post-process</b>：链主路径完成后，Engine 遍历所有 handler，对
 * {@link CacheHandler#requiresPostProcess(CacheContext)} 返回 {@code true}
 * 的 handler 调用其 {@link CacheHandler#afterChainExecution(CacheContext, CacheResult)}。
 * opt-in 语义由类型化的 {@code requiresPostProcess} hook 表达,无需 seam 边界
 * {@code instanceof} type check。失败 try/catch 不污染主链。
 *
 * <p><b>嵌套推进（explicit continuation）</b>：{@link SyncLockHandler} 需要在锁内推进剩余链。
 * 引擎把它作为<b>入参</b>交给当前 handler —— {@link #driveChain} 为每个节点构造
 * {@link ChainContinuation}(绑定该节点的快照位置),经
 * {@link CacheHandler#handle(CacheContext, ChainContinuation)} 传入。handler 的链位置因此
 * 不再是「引擎内部 + 静态 ThreadLocal + indexOf(this)」的隐式环境,而是它手里的一个句柄:
 * 引擎不再持任何静态状态,handler 不再反查引擎,fragment API 与测试专用 setter 一并消失。
 *
 * <p><b>线程安全</b>：Engine 单例 Bean，{@link #observers} 字段为
 * {@link java.util.concurrent.CopyOnWriteArrayList}（启动期单写、热期多读），
 * observer 自身必须线程安全。Handler 列表由 {@link CacheHandlerChain}
 * 完全持有;Engine 内部不修改该列表。
 *
 * <p><b>快照归属</b>:链 list 单一真理源完全收敛在 {@code CacheHandlerChain},
 * Engine 通过 {@link #execute(List, CacheContext)} 接收快照参数,并把「当前节点之后」的
 * 子链以 {@link ChainContinuation} 形态交给正在执行的 handler。
 *
 * <p><b>Observer 列表管理</b>:{@code addObserver} / 遍历逻辑均由 Engine
 * 持有的 CopyOnWriteArrayList 完成。
 */
@Slf4j
@Component
class ChainEngine {

    /** 注册的 observer 列表 — 启动期单写、热期多读。 */
    private final List<ChainObserver> observers = new CopyOnWriteArrayList<>();

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
        if (observer == null) {
            throw new IllegalArgumentException("observer must not be null");
        }
        observers.add(observer);
    }


    /**
     * 执行责任链 — 整条 chain 全生命周期(head handle + post-process + 观测)。
     *
     * <p>接收 {@code snapshot} 作为参数(由 {@link CacheHandlerChain} 在 synchronized
     * 块内拍出);around-hook 配对 + post-process + 异常守护由 {@link ChainLifecycle}
     * 私有内嵌 seam 承担,本方法只做「空链告警 + 委派」2 步。
     *
     * <p>执行流程：
     * <ol>
     *   <li>快照当前 handler 链；空链打 WARN(由 ChainLifecycle 仍跑 around-hook 配对)</li>
     *   <li>所有 observer.onChainStart — ChainLifecycle 入口</li>
     *   <li>节点循环:beforeNode → handler.handle(ctx, continuation) → afterNode → decision switch — driveChain</li>
     *   <li>post-process 遍历 — ChainLifecycle 内部</li>
     *   <li>所有 observer.onChainEnd(即使主路径异常也调用) — ChainLifecycle finally 守护</li>
     * </ol>
     *
     * @param snapshot handler 链快照({@link CacheHandlerChain} 一次性 {@code List.copyOf} 产出)
     * @param context  缓存上下文
     * @return 链执行最终结果(post-process 已执行)
     */
    public CacheResult execute(List<CacheHandler> snapshot, CacheContext context) {
        if (snapshot == null || snapshot.isEmpty()) {
            log.warn("Handler chain is empty!");
        }
        log.debug("Executing handler chain for operation: {}, cacheName: {}, key: {}",
                context.getOperation(), context.getCacheName(), context.getRedisKey());
        return new ChainLifecycle(observers, snapshot, context).run();
    }

    /**
     * 节点推进主循环 — 只负责按 snapshot index 顺序推进控制流。观测派发由当前
     * {@link ChainLifecycle} 统一承担:
     * <ol>
     *   <li>检测 context.isSkipRemaining() — 短路返回 success</li>
     *   <li>交给 observation seam 调度当前节点及其 observer hooks</li>
     *   <li>decision switch（CONTINUE 推进下一 index / SKIP_ALL 物化 / TERMINATE 终止）</li>
     * </ol>
     *
     * <p><b>并发隔离</b>：snapshot 为 {@link CacheHandlerChain} 一次性拍出的不可变
     * {@code List.copyOf} 产出，index 推进在快照内读取；{@code addHandler} 改链
     * 仅影响下次快照，当前 {@code execute} 持有的快照引用完全隔离。Engine 无静态状态,
     * 并发 execute 互不干扰。
     *
     * @param snapshot 不可变 handler 链快照（Engine 只读，不修改）
     * @param context 缓存上下文
     * @param lifecycle 当前 execute 的 observation seam
     */
    private CacheResult driveChain(List<CacheHandler> snapshot, CacheContext context,
                                   ChainLifecycle lifecycle) {
        for (int idx = 0; idx < snapshot.size(); idx++) {
            // 上游 SKIP_ALL 已物化：短路返回 success
            if (context.isSkipRemaining()) {
                return CacheResult.success();
            }
            CacheHandler current = snapshot.get(idx);
            NodeContinuation next = continuationFor(snapshot, idx, context, lifecycle);
            HandlerResult result = lifecycle.invokeNode(current, context, next);

            if (result == null) {
                // SPI 协议(RM-007):handler 必须返回非 null HandlerResult。
                // null 以显式协议异常拒绝并指名违规 handler,不以 NPE 形式失败。
                throw new IllegalStateException(
                        "CacheHandler returned null HandlerResult: "
                                + current.getClass().getName());
            }
            switch (result.decision()) {
                case CONTINUE:
                    if (next.advanced()) {
                        // 协议违规:handler 已用句柄把剩余链跑完,又返回 CONTINUE 让引擎再推一遍
                        // —— 后继会对同一请求执行两次(重复 Redis 写 / 重复 bloom 回填 / 二次取锁)。
                        throw new IllegalStateException(
                                "CacheHandler advanced the remainder and then returned CONTINUE: "
                                        + current.getClass().getName()
                                        + " — return TERMINATE after ChainContinuation.advance()");
                    }
                    // 链尾 CONTINUE：返回 handler 的 result（result 为 null 时退化为 success）
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
                    throw new IllegalStateException("Unknown FlowControl: " + result.decision());
            }
        }
        // 空快照（理论由 execute 前置拦截，防御）
        return CacheResult.success();
    }

    /**
     * 为快照中第 {@code index} 个节点构造嵌套推进句柄 —— 绑定 (snapshot, index, context,
     * lifecycle),推进该节点<b>之后</b>的剩余链。
     *
     * <p>语义与旧的 fragment API 等价(跳过 aroundChain 观测与 post-process,由外层
     * {@link #execute} 唯一负责),但起点来自构造期的 index,不再需要 {@code indexOf(from)}
     * 反查,也不需要任何 ThreadLocal。
     */
    private NodeContinuation continuationFor(List<CacheHandler> snapshot, int index,
                                             CacheContext context, ChainLifecycle lifecycle) {
        return new NodeContinuation(snapshot, index, context, lifecycle);
    }

    /**
     * 单节点的推进句柄 —— 每次 {@link #driveChain} 迭代构造一个,生命周期止于该节点返回。
     *
     * <p>{@code used} 用普通字段而非原子量:契约要求句柄只在<b>当前线程</b>内使用
     * (见 {@link ChainContinuation}),违规使用的最坏后果是重复推进一次后继链,不值得为
     * 每次缓存操作多分配一个原子量。
     */
    private final class NodeContinuation implements ChainContinuation {

        private final List<CacheHandler> snapshot;
        private final int index;
        private final CacheContext context;
        private final ChainLifecycle lifecycle;
        private boolean used;

        NodeContinuation(List<CacheHandler> snapshot, int index, CacheContext context,
                         ChainLifecycle lifecycle) {
            this.snapshot = snapshot;
            this.index = index;
            this.context = context;
            this.lifecycle = lifecycle;
        }

        /** 句柄是否已被推进 —— 引擎据此拒绝「推进后仍返回 CONTINUE」的协议违规。 */
        boolean advanced() {
            return used;
        }

        @Override
        public CacheResult advance() {
            if (used) {
                throw new IllegalStateException(
                        "ChainContinuation.advance() called more than once for handler #" + index
                                + " of " + snapshot.size());
            }
            used = true;
            if (index + 1 >= snapshot.size()) {
                // 已是链尾:无后继可推进
                return CacheResult.success();
            }
            // 不可变快照的 subList view — driveChain 只读（get / size），view 安全
            return driveChain(snapshot.subList(index + 1, snapshot.size()), context, lifecycle);
        }
    }


    /**
     * 把 {@link HandlerResult} 物化为 {@link CacheResult} —— null 退化为 success
     * 的单一权威 helper,三处 decision 分支走同一行委派,deletion test 保护语义。
     */
    private static CacheResult materialize(HandlerResult result) {
        return result.result() != null ? result.result() : CacheResult.success();
    }

    // ==================== ChainLifecycle observation seam ====================

    /**
     * 责任链全生命周期与 observer 派发 seam.
     *
     * <p>{@link ObserverDispatch} 统一拥有两条观测路径的 observer 快照、
     * positional token 配对和逐 hook 异常隔离；ChainLifecycle 只编排链入口/出口、
     * 节点调用以及 post-process。节点每次调用都创建自己的 dispatch 快照，保持
     * 节点级 observer snapshot 语义；链级 start/end 共用一份快照。
     */
    private final class ChainLifecycle {

        private final List<ChainObserver> observers;
        private final List<CacheHandler> snapshot;
        private final CacheContext context;

        ChainLifecycle(List<ChainObserver> observers,
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
         * <p>driveChain 抛出的异常继续向上冒泡;onChainEnd 由 finally 守护保证触发。
         */
        CacheResult run() {
            ObserverDispatch observation = new ObserverDispatch();
            Object[] scopeTokens = observation.start(
                    "onChainStart", observer -> observer.onChainStart(context));
            CacheResult mainResult = null;
            try {
                if (snapshot == null || snapshot.isEmpty()) {
                    // 空链仍是正常完成,保持 success 语义
                    mainResult = CacheResult.success();
                } else {
                    mainResult = driveChain(snapshot, context, this);
                    runPostProcess(mainResult);
                }
            } finally {
                observation.finish(
                        "onChainEnd",
                        scopeTokens,
                        mainResult,
                        (observer, token, result) ->
                                observer.onChainEnd(context, token, (CacheResult) result));
            }
            return mainResult;
        }

        /**
         * 单节点调用：onNodeStart → beforeNode → handler.handle(ctx, next) → afterNode →
         * onNodeEnd。handler 异常仍向调用方冒泡；token 化的 onNodeEnd 由 finally
         * 配对，避免 around-node observer 泄漏调用状态。
         */
        HandlerResult invokeNode(CacheHandler handler, CacheContext nodeContext,
                                 ChainContinuation next) {
            // 每个节点单独拍 observer 快照,保持节点间注册变更隔离语义。
            ObserverDispatch observation = new ObserverDispatch();
            Object[] scopeTokens = observation.start(
                    "onNodeStart", observer -> observer.onNodeStart(handler, nodeContext));
            HandlerResult result = null;
            try {
                observation.each(
                        "beforeNode",
                        observer -> observer.beforeNode(handler, nodeContext));
                result = handler.handle(nodeContext, next);
                HandlerResult completedResult = result;
                observation.each(
                        "afterNode",
                        observer -> observer.afterNode(handler, nodeContext, completedResult));
                return result;
            } finally {
                observation.finish(
                        "onNodeEnd",
                        scopeTokens,
                        result,
                        (observer, token, completedResult) ->
                                observer.onNodeEnd(
                                        handler, nodeContext, token, (HandlerResult) completedResult));
            }
        }

        /**
         * post-process 遍历.
         *
         * <p>失败 try/catch 不污染主链,打 ERROR 日志。
         */
        private void runPostProcess(CacheResult mainResult) {
            for (CacheHandler handler : snapshot) {
                try {
                    if (!handler.requiresPostProcess(context)) {
                        continue;
                    }
                    handler.afterChainExecution(context, mainResult);
                    log.debug("Post-processing executed for: {}",
                            CacheHandlerChain.handlerTag(handler));
                } catch (Exception e) {
                    FailureReport.error(log,
                            "Post-processing failed for " + CacheHandlerChain.handlerTag(handler)
                                    + ", operation: " + context.getOperation(),
                            context.getCacheName(), null, e);
                }
            }
        }

        /**
         * One observer snapshot plus shared positional token/error protocol for either
         * chain-level or node-level dispatch.
         */
        private final class ObserverDispatch {

            private final List<ChainObserver> observerList = List.copyOf(observers);

            Object[] start(String hookName, ObserverStartHook hook) {
                Object[] scopeTokens = new Object[observerList.size()];
                for (int i = 0; i < observerList.size(); i++) {
                    ChainObserver observer = observerList.get(i);
                    try {
                        scopeTokens[i] = hook.invoke(observer);
                    } catch (Exception ex) {
                        logFailure(observer, hookName, ex);
                    }
                }
                return scopeTokens;
            }

            void each(String hookName, ObserverHook hook) {
                for (ChainObserver observer : observerList) {
                    try {
                        hook.invoke(observer);
                    } catch (Exception ex) {
                        logFailure(observer, hookName, ex);
                    }
                }
            }

            void finish(String hookName, Object[] scopeTokens, Object result,
                        ObserverEndHook hook) {
                for (int i = 0; i < observerList.size(); i++) {
                    ChainObserver observer = observerList.get(i);
                    try {
                        hook.invoke(observer, scopeTokens[i], result);
                    } catch (Exception ex) {
                        logFailure(observer, hookName, ex);
                    }
                }
            }

            private void logFailure(ChainObserver observer, String hookName, Exception ex) {
                FailureReport.error(log,
                        "Observer " + observer.getClass().getSimpleName() + " " + hookName + " failed", ex);
            }

            @FunctionalInterface
            private interface ObserverStartHook {
                Object invoke(ChainObserver observer);
            }

            @FunctionalInterface
            private interface ObserverHook {
                void invoke(ChainObserver observer);
            }

            @FunctionalInterface
            private interface ObserverEndHook {
                void invoke(ChainObserver observer, Object scopeToken, Object result);
            }
        }
    }



}
