package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.ChainContinuation;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.observer.ChainObserver;
import java.util.List;
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
 * {@link ObserverRegistry}（内部 {@code CopyOnWriteArrayList}，启动期单写、热期
 * 多读），observer 自身必须线程安全。Handler 列表由 {@link CacheHandlerChain}
 * 完全持有;Engine 内部不修改该列表。
 *
 * <p><b>快照归属</b>:链 list 单一真理源完全收敛在 {@code CacheHandlerChain},
 * Engine 通过 {@link #execute(List, CacheContext)} 接收快照参数,并把「当前节点之后」的
 * 子链以 {@link ChainContinuation} 形态交给正在执行的 handler。
 *
 * <p><b>Observer 列表管理委派</b>:{@code addObserver} / {@code observers}
 * / 遍历逻辑委派到 {@link ObserverRegistry} 单一 seam,与
 * {@code handler.AnnotationChainEngine} 共用,消除两 engine 间的 observer
 * 列表样板重复。
 */
@Slf4j
@Component
class ChainEngine {

    /** 注册的 observer 列表 — 委派到 {@link ObserverRegistry} 单一 seam. */
    private final ObserverRegistry observers = new ObserverRegistry();

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
     * 节点推进主循环 — 按 snapshot index 顺序推进:
     * <ol>
     *   <li>检测 context.isSkipRemaining() — 短路返回 success</li>
     *   <li>observer.beforeNode</li>
     *   <li>handler.handle(ctx, next) — next 为「本节点之后剩余链」的推进句柄</li>
     *   <li>observer.afterNode</li>
     *   <li>decision switch（CONTINUE 推进下一 index / SKIP_ALL 物化 / TERMINATE 终止）</li>
     * </ol>
     *
     * <p><b>并发隔离</b>：snapshot 为 {@link CacheHandlerChain} 一次性拍出的不可变
     * {@code List.copyOf} 产出，index 推进在快照内读取；{@code addHandler} 改链
     * 仅影响下次快照，当前 {@code execute} 持有的快照引用完全隔离。Engine 无静态状态,
     * 并发 execute 互不干扰。
     *
     * @param snapshot 不可变 handler 链快照（Engine 只读，不修改）
     */
    private CacheResult driveChain(List<CacheHandler> snapshot, CacheContext context) {
        for (int idx = 0; idx < snapshot.size(); idx++) {
            // 上游 SKIP_ALL 已物化：短路返回 success
            if (context.isSkipRemaining()) {
                return CacheResult.success();
            }
            CacheHandler current = snapshot.get(idx);
            NodeContinuation next = continuationFor(snapshot, idx, context);
            HandlerResult result = invokeWithObservers(current, context, next);

            if (result == null) {
                // SPI 协议(RM-007):handler 必须返回非 null HandlerResult。
                // null 以显式协议异常拒绝并指名违规 handler,不以 NPE 形式失败。
                throw new IllegalStateException(
                        "CacheHandler returned null HandlerResult: "
                                + current.getClass().getName());
            }
            if (result.decision() == null) {
                // SPI 协议要求 HandlerResult 携带非 null decision,否则引擎无法分发控制流。
                throw new IllegalStateException(
                        "CacheHandler returned HandlerResult with null decision: "
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
     * 为快照中第 {@code index} 个节点构造嵌套推进句柄 —— 绑定 (snapshot, index, context),
     * 推进该节点<b>之后</b>的剩余链。
     *
     * <p>语义与旧的 fragment API 等价(跳过 aroundChain 观测与 post-process,由外层
     * {@link #execute} 唯一负责),但起点来自构造期的 index,不再需要 {@code indexOf(from)}
     * 反查,也不需要任何 ThreadLocal。
     */
    private NodeContinuation continuationFor(List<CacheHandler> snapshot, int index,
                                             CacheContext context) {
        return new NodeContinuation(snapshot, index, context);
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
        private boolean used;

        NodeContinuation(List<CacheHandler> snapshot, int index, CacheContext context) {
            this.snapshot = snapshot;
            this.index = index;
            this.context = context;
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
            return driveChain(snapshot.subList(index + 1, snapshot.size()), context);
        }
    }

    /**
     * 把 {@link HandlerResult} 物化为 {@link CacheResult} —— null 退化为 success
     * 的单一权威 helper,三处 decision 分支走同一行委派,deletion test 保护语义。
     */
    private static CacheResult materialize(HandlerResult result) {
        return result.result() != null ? result.result() : CacheResult.success();
    }

    /**
     * 单节点调用：onNodeStart → beforeNode → handler.handle(ctx, next) → afterNode →
     * onNodeEnd。Engine 不捕获 handler 异常，异常仍向调用方冒泡；但 token 化的
     * onNodeEnd 由 finally 配对，避免计时等 around-node observer 泄漏调用状态。
     *
     * <p>beforeNode/afterNode 契约：handler 抛异常时 afterNode 不调用，因而
     * DEBUG log / fired counter 不会把失败求值计作成功结果。onNodeEnd 此时收到
     * null result，只负责回收 token，不应伪造 decision。
     *
     * @param next 本节点之后剩余链的推进句柄(handler 可选用,默认实现忽略)
     */
    private HandlerResult invokeWithObservers(CacheHandler handler, CacheContext context,
                                              ChainContinuation next) {
        List<ChainObserver> observerList = observers.snapshot();
        Object[] scopeTokens = new Object[observerList.size()];
        for (int i = 0; i < observerList.size(); i++) {
            ChainObserver observer = observerList.get(i);
            try {
                scopeTokens[i] = observer.onNodeStart(handler, context);
            } catch (Exception ex) {
                log.error("Observer {} onNodeStart failed: {}",
                        observer.getClass().getSimpleName(), ex.toString(), ex);
            }
        }

        HandlerResult result = null;
        try {
            for (ChainObserver observer : observerList) {
                try {
                    observer.beforeNode(handler, context);
                } catch (Exception ex) {
                    log.error("Observer {} beforeNode failed: {}",
                            observer.getClass().getSimpleName(), ex.toString(), ex);
                }
            }
            result = handler.handle(context, next);
            HandlerResult completedResult = result;
            for (ChainObserver observer : observerList) {
                try {
                    observer.afterNode(handler, context, completedResult);
                } catch (Exception ex) {
                    log.error("Observer {} afterNode failed: {}",
                            observer.getClass().getSimpleName(), ex.toString(), ex);
                }
            }
            return result;
        } finally {
            for (int i = 0; i < observerList.size(); i++) {
                ChainObserver observer = observerList.get(i);
                try {
                    observer.onNodeEnd(handler, context, scopeTokens[i], result);
                } catch (Exception ex) {
                    log.error("Observer {} onNodeEnd failed: {}",
                            observer.getClass().getSimpleName(), ex.toString(), ex);
                }
            }
        }
    }

    // ==================== ChainLifecycle seam ====================

    /**
     * 责任链全生命周期守护 — 私有 seam,封装 execute 的 4 件交织关注点:
     * <ol>
     *   <li><b>around-hook 配对</b>:onChainStart → driveChain + post-process → onChainEnd
     *       (即使主路径异常也调用 onChainEnd,保证 observer 资源配对 — 防止 MDC / Timer
     *       跨 execute 调用的资源泄漏)</li>
     *   <li><b>post-process 遍历</b>:对所有 {@code requiresPostProcess} opt-in 的
     *       handler 调用 {@code afterChainExecution},失败 try/catch 隔离不污染主链</li>
     *   <li><b>异常守护</b>:driveChain 抛出的异常继续向上冒泡,onChainEnd 仍由
     *       finally 触发</li>
     *   <li><b>空链短路</b>:snapshot 为空时仍配对 around-hook(observer 可能在 start
     *       注册 thread-local 资源如 Timer.Sample,不配对会泄漏),但跳过 driveChain
     *       + post-process</li>
     * </ol>
     *
     * <p><b>scope token 配对</b>:onChainStart 收集每个 observer 返回的 scope token,
     * onChainEnd 按相同 observer 顺序回传(逐个 observer 配对,跨 observer 不混淆)。
     * Engine 不感知 token 内部协议 —— observer 状态机完全自承,CacheContext 不
     * 承担 stringly-typed 通用 attributes 袋。
     *
     * <p><b>设计纪律</b>:
     * <ul>
     *   <li>private final 嵌套类(非 static)— 不暴露给外部(只服务 ChainEngine.execute
     *       一处);非 static 因需调外部 instance method {@code driveChain},持 outer
     *       reference 是 locality 提升而非泄漏</li>
     *   <li>onChainEnd 传入主路径 + post-process 后的 {@code mainResult}；
     *       正常完成时与 execute 返回值一致，主路径异常时为 {@code null}，
     *       且 execute 继续向上冒泡原异常</li>
     *   <li>run() 无参(不返回 mainResult 后再由 caller 收 mainResult),避免与 caller
     *       形成 split-knowledge</li>
     * </ul>
     *
     * <p><b>deletion test</b>:把 ChainLifecycle 删掉、内联回 execute → execute 回归
     * 多层 try/finally 嵌套 + around-end 在 2 处独立写 2 遍,复杂度上升。本 seam 浓缩。
     */
    private final class ChainLifecycle {

        private final ObserverRegistry observers;
        private final List<CacheHandler> snapshot;
        private final CacheContext context;

        ChainLifecycle(ObserverRegistry observers,
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
         *
         * <p><b>scope token 收集</b>:around-start 阶段逐个调 observer 的 {@code onChainStart},把每个 observer 返回的 scope token 写入
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
            CacheResult mainResult = null;
            try {
                if (snapshot == null || snapshot.isEmpty()) {
                    // 空链仍是正常完成,保持 success 语义
                    mainResult = CacheResult.success();
                } else {
                    mainResult = driveChain(snapshot, context);
                    runPostProcess(mainResult);
                }
            } finally {
                for (int i = 0; i < observerList.size(); i++) {
                    ChainObserver o = observerList.get(i);
                    try {
                        o.onChainEnd(context, scopeTokens[i], mainResult);
                    } catch (Exception ex) {
                        log.error("Observer {} onChainEnd failed: {}",
                                o.getClass().getSimpleName(), ex.toString(), ex);
                    }
                }
            }
            return mainResult;
        }

        /**
         * post-process 遍历.
         *
         * <p>失败 try/catch 不污染主链,打 ERROR 日志。
         */
        private void runPostProcess(CacheResult mainResult) {
            for (CacheHandler handler : snapshot) {
                if (handler.requiresPostProcess(context)) {
                    try {
                        handler.afterChainExecution(context, mainResult);
                        log.debug("Post-processing executed for: {}",
                                handler.getClass().getSimpleName());
                    } catch (Exception e) {
                        // ADR-0001 §15 key 隐私:ERROR 只带 cacheName + 异常类型链,不带 raw key;
                        // 完整栈留 DEBUG(异常 message 可能内嵌 key)。
                        log.error("Post-processing failed for: {}, operation: {}, cacheName: {}, cause={}",
                                handler.getClass().getSimpleName(),
                                context.getOperation(),
                                context.getCacheName(),
                                FailureDiagnostics.sanitizedFailure(e));
                        log.debug("Post-processing failure detail: cacheName={}",
                                context.getCacheName(), e);
                    }
                }
            }
        }
    }
}
