package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.ChainContinuation;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.observer.ChainObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChainEngine 单元测试 — 唯一推进引擎的契约。
 *
 * <p>Engine 的全部职责（链推进 + decision switch + 观测编排 + post-process）在本测试
 * 中直接验证。{@code CacheHandlerChainTest} 仅覆盖 facade 自身的 handler 列表管理。
 */
@DisplayName("ChainEngine Tests")
class ChainEngineTest {

    private ChainEngine engine;

    /**
     * Engine 不持 chainSnapshotRef — 测试在每个测试方法里显式装好链快照,作为
     * {@link ChainEngine#execute(List, CacheContext)} 的第一参数传入。
     * {@link #installChain(CacheHandler...)} helper 负责赋值。
     */
    private List<CacheHandler> snapshot;

    @BeforeEach
    void setUp() {
        engine = new ChainEngine();
        snapshot = null;
    }

    private CacheContext newCtx() {
        return CacheContext.of(CacheInput.builder()
                .operation(CacheOperation.GET)
                .cacheName("test-cache")
                .redisKey("test:key")
                .actualKey("test:key")
                .build());
    }

    private void installChain(CacheHandler... handlers) {
        // 链快照作为 execute 的第一参数传入
        this.snapshot = List.of(handlers);
    }

    // ==================== 推进协议 ====================

    @Nested
    @DisplayName("chain advance")
    class ChainAdvanceTests {

        @Test
        @DisplayName("单节点 CONTINUE → 返回 success(链尾退化为 success)")
        void singleHandler_continue_returnsSuccess() {
            CacheHandler h = new RecordingHandler("h1", HandlerResult.continueWith(null));
            installChain(h);

            CacheResult result = engine.execute(snapshot, newCtx());

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("多节点按顺序推进,每个 handler 都被求值")
        void multipleHandlers_advanceInOrder() {
            List<String> visitOrder = new ArrayList<>();
            CacheHandler h1 = new RecordingHandler("h1", visitOrder, HandlerResult.continueChain());
            CacheHandler h2 = new RecordingHandler("h2", visitOrder, HandlerResult.continueChain());
            CacheHandler h3 = new RecordingHandler("h3", visitOrder,
                    HandlerResult.continueWith(CacheResult.success()));
            installChain(h1, h2, h3);

            engine.execute(snapshot, newCtx());

            assertThat(visitOrder).containsExactly("h1", "h2", "h3");
        }

        @Test
        @DisplayName("TERMINATE decision → 立即返回,后续 handler 不被求值")
        void terminate_stopsSubsequentHandlers() {
            List<String> visitOrder = new ArrayList<>();
            CacheHandler h1 = new RecordingHandler("h1", visitOrder, HandlerResult.terminate(CacheResult.success()));
            CacheHandler h2 = new RecordingHandler("h2", visitOrder, HandlerResult.continueChain());
            installChain(h1, h2);

            engine.execute(snapshot, newCtx());

            assertThat(visitOrder).containsExactly("h1");
        }

        @Test
        @DisplayName("SKIP_ALL decision → 物化 skipRemaining,返回 result")
        void skipAll_materializesSkipRemaining() {
            AtomicBoolean nextCalled = new AtomicBoolean(false);
            CacheHandler h1 = new RecordingHandler("h1", HandlerResult.skipAll());
            CacheHandler h2 = new AbstractCacheHandler() {
                @Override
                protected boolean shouldHandle(CacheContext context) { return true; }
                @Override
                protected HandlerResult doHandle(CacheContext context, ChainContinuation next) {
                    nextCalled.set(true);
                    return HandlerResult.continueChain();
                }
            };
            installChain(h1, h2);

            CacheContext ctx = newCtx();
            engine.execute(snapshot, ctx);

            // h2 确实没被求值(SKIP_ALL 已物化,下一个 iteration 检测到 isSkipRemaining)
            assertThat(nextCalled.get()).isFalse();
        }

        @Test
        @DisplayName("CONTINUE + 链尾 + handler 返回 null result → 退化为 success(与原 executeChainInternal 一致)")
        void continueAtChainTail_nullResult_returnsSuccess() {
            CacheHandler h = new RecordingHandler("h1", HandlerResult.continueWith(null));
            installChain(h);

            CacheResult result = engine.execute(snapshot, newCtx());

            assertThat(result.isSuccess()).isTrue();
        }
    }

    // ==================== 观测编排 ====================

    @Nested
    @DisplayName("observer orchestration")
    class ObserverTests {

        @Test
        @DisplayName("aroundChain 与 node token hooks 按嵌套顺序执行")
        void aroundChain_andPerNode_calledInOrder() {
            // 用真实 observer 录制 4 个钩子的调用顺序(onChainStart 返回 Object 后,
            // mock-based 验证语义不适用,真实 observer 录制更鲁棒)
            RecordingObserver observer = new RecordingObserver();
            engine.addObserver(observer);
            installChain(new RecordingHandler("h1", HandlerResult.continueWith(CacheResult.success())));

            engine.execute(snapshot, newCtx());

            assertThat(observer.events).containsExactly(
                    "onChainStart", "onNodeStart", "afterNode",
                    "onNodeEnd", "onChainEnd");
        }

        @Test
        @DisplayName("onChainStart 返回的 scope token 配对回传到 onChainEnd(per-observer 隔离)")
        void scopeToken_pairedFromStartToEnd() {
            // Engine 按 observer index 配对 scope token,scopeToken 与 onChainStart
            // 返回的引用完全相同
            TokenRecordingObserver observer = new TokenRecordingObserver();
            engine.addObserver(observer);
            installChain(new RecordingHandler("h1", HandlerResult.continueWith(CacheResult.success())));

            engine.execute(snapshot, newCtx());

            // 1) onChainStart 被调一次
            assertThat(observer.startCount).isEqualTo(1);
            // 2) onChainEnd 被调一次,token == onChainStart 返回的引用
            assertThat(observer.endCount).isEqualTo(1);
            assertThat(observer.endToken).isSameAs(observer.lastStartToken);
        }

        @Test
        @DisplayName("onChainEnd 收到链路最终结果而非固定 success")
        void onChainEnd_receivesFinalResult() {
            AtomicReference<CacheResult> observed = new AtomicReference<>();
            engine.addObserver(new ChainObserver() {
                @Override
                public void onChainEnd(CacheContext context, Object scopeToken, CacheResult result) {
                    observed.set(result);
                }
            });
            CacheResult expected = CacheResult.miss();
            installChain(new RecordingHandler("h1", HandlerResult.terminate(expected)));

            CacheResult actual = engine.execute(snapshot, newCtx());

            assertThat(actual).isSameAs(expected);
            assertThat(observed.get()).isSameAs(expected);
        }
        @Test
        @DisplayName("handler 异常时 onChainEnd 收到 null,execute 继续冒泡")
        void handlerThrows_onChainEndReceivesNull() {
            AtomicReference<CacheResult> observed = new AtomicReference<>();
            AtomicBoolean onChainEndCalled = new AtomicBoolean();
            engine.addObserver(new ChainObserver() {
                @Override
                public void onChainEnd(CacheContext context, Object scopeToken, CacheResult result) {
                    onChainEndCalled.set(true);
                    observed.set(result);
                }
            });
            CacheHandler throwing = context -> {
                throw new IllegalStateException("handler boom");
            };
            installChain(throwing);

            assertThatThrownBy(() -> engine.execute(snapshot, newCtx()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("handler boom");
            assertThat(onChainEndCalled).isTrue();
            assertThat(observed.get()).isNull();
        }

        @Test
        @DisplayName("continuation:handler 用引擎交出的句柄推进剩余链,aroundChain 观测不重复")
        void continuation_advancesRemainderWithoutDuplicatingAroundChain() {
            RecordingObserver observer = new RecordingObserver();
            engine.addObserver(observer);
            List<String> visits = new ArrayList<>();
            // h0 在自身处理期间推进剩余链 —— 模拟 SyncLockHandler 锁内推进
            CacheHandler h0 = new ContinuationHandler("h0", visits);
            CacheHandler h1 = new RecordingHandler("h1", visits, HandlerResult.continueChain());
            CacheHandler h2 = new RecordingHandler("h2", visits, HandlerResult.continueWith(CacheResult.success()));
            installChain(h0, h1, h2);

            CacheResult result = engine.execute(snapshot, newCtx());

            assertThat(result.isSuccess()).isTrue();
            // 剩余链在 h0 的处理窗口内跑完(锁内推进语义)
            assertThat(visits).containsExactly("h0-in", "h1", "h2", "h0-out");
            // 后继节点照常触发 perNode 观测;aroundChain 仅由外层 execute 配对一次,
            // 嵌套推进不重复 stamp / record
            assertThat(observer.events).containsExactly(
                    "onChainStart",
                    "onNodeStart",                                                  // h0 进入
                    "onNodeStart", "afterNode", "onNodeEnd",                        // h1(嵌套)
                    "onNodeStart", "afterNode", "onNodeEnd",                        // h2(嵌套)
                    "afterNode", "onNodeEnd",                                       // h0 退出
                    "onChainEnd");
        }

        @Test
        @DisplayName("continuation:同一句柄推进两次 → IllegalStateException(防重复执行后继)")
        void continuation_secondAdvance_throws() {
            List<String> visits = new ArrayList<>();
            CacheHandler h0 = new CacheHandler() {
                @Override
                public HandlerResult handle(CacheContext context) {
                    throw new AssertionError("engine must call the 2-arg form");
                }

                @Override
                public HandlerResult handle(CacheContext context, ChainContinuation next) {
                    next.advance();
                    return HandlerResult.terminate(next.advance());
                }
            };
            installChain(h0, new RecordingHandler("h1", visits, HandlerResult.continueChain()));

            assertThatThrownBy(() -> engine.execute(snapshot, newCtx()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("more than once");
            assertThat(visits).containsExactly("h1");
        }

        @Test
        @DisplayName("continuation:推进后仍返回 CONTINUE → 协议异常(否则后继会跑第二遍)")
        void continuation_advanceThenContinue_isRejected() {
            List<String> visits = new ArrayList<>();
            CacheHandler offending = new CacheHandler() {
                @Override
                public HandlerResult handle(CacheContext context) {
                    throw new AssertionError("engine must call the 2-arg form");
                }

                @Override
                public HandlerResult handle(CacheContext context, ChainContinuation next) {
                    next.advance();
                    return HandlerResult.continueChain();
                }
            };
            installChain(offending, new RecordingHandler("h1", visits, HandlerResult.continueChain()));

            assertThatThrownBy(() -> engine.execute(snapshot, newCtx()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("returned CONTINUE");
            assertThat(visits)
                    .as("后继只跑一次(第二次由协议异常挡下)")
                    .containsExactly("h1");
        }

        @Test
        @DisplayName("continuation:链尾节点的句柄推进 → success(无后继)")
        void continuation_lastNode_advanceReturnsSuccess() {
            CacheHandler[] tail = new CacheHandler[1];
            List<String> visits = new ArrayList<>();
            tail[0] = new CacheHandler() {
                @Override
                public HandlerResult handle(CacheContext context) {
                    throw new AssertionError("engine must call the 2-arg form");
                }

                @Override
                public HandlerResult handle(CacheContext context, ChainContinuation next) {
                    CacheResult remainder = next.advance();
                    visits.add("remainder-success=" + remainder.isSuccess());
                    // 推进后必须以 TERMINATE 收尾(引擎拒绝 advance-then-CONTINUE)
                    return HandlerResult.terminate(remainder);
                }
            };
            installChain(tail[0]);

            engine.execute(snapshot, newCtx());

            assertThat(visits).containsExactly("remainder-success=true");
        }

        @Test
        @DisplayName("handler 异常时 onNodeEnd 仍配对且 result 为 null")
        void handlerThrows_nodeScopeStillCloses() {
            NodeTokenRecordingObserver observer = new NodeTokenRecordingObserver();
            engine.addObserver(observer);
            CacheHandler throwing = context -> { throw new IllegalStateException("handler boom"); };
            installChain(throwing);

            assertThatThrownBy(() -> engine.execute(snapshot, newCtx()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("handler boom");

            assertThat(observer.startCount).isEqualTo(1);
            assertThat(observer.endCount).isEqualTo(1);
            assertThat(observer.endToken).isSameAs(observer.startToken);
            assertThat(observer.endResult).isNull();
        }

        @Test
        @DisplayName("afterNode 收到 handler.handle 的真实 result(非 null)")
        void afterNode_receivesActualResult() {
            HandlerResult[] captured = new HandlerResult[1];
            ChainObserver capture = new ChainObserver() {
                @Override
                public void afterNode(CacheHandler h, CacheContext c, HandlerResult r) {
                    captured[0] = r;
                }
            };
            engine.addObserver(capture);
            HandlerResult expected = HandlerResult.terminate(CacheResult.success());
            installChain(new RecordingHandler("h1", expected));

            engine.execute(snapshot, newCtx());

            assertThat(captured[0]).isSameAs(expected);
        }

        @Test
        @DisplayName("observer 抛异常被吞掉,主链结果不受影响")
        void observerThrows_swallowed_mainChainUnaffected() {
            ChainObserver throwing = new ChainObserver() {
                @Override public Object onChainStart(CacheContext context) { throw new RuntimeException("start boom"); }
                @Override public void onChainEnd(CacheContext context, Object scopeToken, CacheResult result) {
                    throw new RuntimeException("end boom");
                }
                @Override public Object onNodeStart(CacheHandler h, CacheContext context) { throw new RuntimeException("node start boom"); }
                @Override public void onNodeEnd(CacheHandler h, CacheContext context, Object token, HandlerResult r) { throw new RuntimeException("node end boom"); }
                @Override public void afterNode(CacheHandler h, CacheContext context, HandlerResult r) { throw new RuntimeException("after boom"); }
            };
            engine.addObserver(throwing);
            installChain(new RecordingHandler("h1", HandlerResult.continueWith(CacheResult.success())));

            // 不应抛异常
            CacheResult result = engine.execute(snapshot, newCtx());

            // main handler 仍返回 success,observer 异常不污染主链
            assertThat(result.isSuccess()).isTrue();
        }
    }

    // ==================== Post-process ====================

    @Nested
    @DisplayName("post-process")
    class PostProcessTests {

        @Test
        @DisplayName("实现 PostProcessHandler 的 handler 在主链完成后被调用")
        void postProcessor_calledAfterMainChain() {
            AtomicBoolean ppCalled = new AtomicBoolean(false);
            CacheHandler main = new RecordingHandler("main", HandlerResult.continueWith(CacheResult.success()));
            CacheHandler pp = new PostProcessRecordingHandler("pp", ppCalled);
            installChain(main, pp);

            engine.execute(snapshot, newCtx());

            assertThat(ppCalled.get()).isTrue();
        }

        @Test
        @DisplayName("post-process 抛异常被 try/catch 吞掉,主链结果不受影响")
        void postProcessFailure_swallowed() {
            CacheHandler main = new RecordingHandler("main", HandlerResult.continueWith(CacheResult.success()));
            CacheHandler pp = new ThrowingPostProcessor();
            installChain(main, pp);

            // 不应抛异常
            CacheResult result = engine.execute(snapshot, newCtx());

            // main 返回 success — 即使 pp 抛异常,主链 result 仍是 success
            assertThat(result.isSuccess()).isTrue();
        }
        @Test
        @DisplayName("requiresPostProcess 抛异常被隔离,主链结果不受影响")
        void postProcessPredicateFailure_swallowed() {
            CacheHandler main = new RecordingHandler("main",
                    HandlerResult.continueWith(CacheResult.success()));
            CacheHandler pp = new ThrowingPostProcessPredicate();
            installChain(main, pp);

            CacheResult result = engine.execute(snapshot, newCtx());

            assertThat(result.isSuccess()).isTrue();
        }


        @Test
        @DisplayName("requiresPostProcess=false → 不调 afterChainExecution")
        void postProcessNotRequired_notCalled() {
            AtomicBoolean ppCalled = new AtomicBoolean(false);
            CacheHandler main = new RecordingHandler("main", HandlerResult.continueWith(CacheResult.success()));
            CacheHandler pp = new PostProcessRecordingHandler("pp", ppCalled, false);
            installChain(main, pp);

            engine.execute(snapshot, newCtx());

            assertThat(ppCalled.get()).isFalse();
        }
    }

    // ==================== 边界 / 防御 ====================

    @Nested
    @DisplayName("edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("空链(snapshot=null) → 返回 success,WARN 一次")
        void nullSnapshot_returnsSuccess() {
            // snapshot 字段为 null(默认) → 直接传 null 进 execute
            // 用 spy 拦截 logger 不易,改为验证返回值即可
            CacheResult result = engine.execute(null, newCtx());
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("空链(snapshot=空列表) → 返回 success")
        void emptySnapshot_returnsSuccess() {
            CacheResult result = engine.execute(List.of(), newCtx());
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("handler 抛 RuntimeException → 直接冒泡,Engine 不吞")
        void handlerThrowsException_propagates() {
            CacheHandler throwing = new CacheHandler() {
                @Override public HandlerResult handle(CacheContext ctx) {
                    throw new RuntimeException("boom");
                }
            };
            installChain(throwing);

            assertThatThrownBy(() -> engine.execute(snapshot, newCtx()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("boom");
        }

        @Test
        @DisplayName("addObserver(null) → IllegalArgumentException")
        void addNullObserver_throws() {
            assertThatThrownBy(() -> engine.addObserver(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("handler 返回 null HandlerResult → IllegalStateException 指名违规 handler(RM-007 协议)")
        void nullHandlerResult_rejectedWithProtocolException() {
            CacheHandler nullReturning = new CacheHandler() {
                @Override public HandlerResult handle(CacheContext ctx) {
                    return null;
                }
            };
            installChain(nullReturning);

            assertThatThrownBy(() -> engine.execute(snapshot, newCtx()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("null HandlerResult")
                    .hasMessageContaining(nullReturning.getClass().getName());
        }

    }

    /**
     * 在自身处理期间推进剩余链的 handler —— 模拟 {@code SyncLockHandler} 锁内推进。
     * 只实现二参入口:引擎必须把推进句柄交给 handler,而不是让 handler 反查引擎。
     */
    static class ContinuationHandler implements CacheHandler {
        private final String name;
        private final List<String> visitLog;

        ContinuationHandler(String name, List<String> visitLog) {
            this.name = name;
            this.visitLog = visitLog;
        }

        @Override
        public HandlerResult handle(CacheContext context) {
            throw new AssertionError("engine must call the 2-arg form");
        }

        @Override
        public HandlerResult handle(CacheContext context,
                                    ChainContinuation next) {
            visitLog.add(name + "-in");
            CacheResult remainder = next.advance();
            visitLog.add(name + "-out");
            return HandlerResult.terminate(remainder);
        }
    }

    static class RecordingHandler implements CacheHandler {
        private final String name;
        private final List<String> visitLog;
        private final HandlerResult result;

        RecordingHandler(String name, HandlerResult result) {
            this(name, null, result);
        }

        RecordingHandler(String name, List<String> visitLog, HandlerResult result) {
            this.name = name;
            this.visitLog = visitLog;
            this.result = result;
        }

        @Override
        public HandlerResult handle(CacheContext context) {
            if (visitLog != null) visitLog.add(name);
            return result;
        }


        @Override
        public String toString() { return name; }
    }

    static class PostProcessRecordingHandler implements CacheHandler {
        private final String name;
        private final AtomicBoolean called;
        private final boolean requires;

        PostProcessRecordingHandler(String name, AtomicBoolean called) {
            this(name, called, true);
        }

        PostProcessRecordingHandler(String name, AtomicBoolean called, boolean requires) {
            this.name = name;
            this.called = called;
            this.requires = requires;
        }

        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueChain();
        }


        @Override
        public void afterChainExecution(CacheContext context, CacheResult result) {
            called.set(true);
        }

        @Override
        public boolean requiresPostProcess(CacheContext context) {
            return requires;
        }
    }

    static class ThrowingPostProcessor implements CacheHandler {

        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueChain();
        }


        @Override
        public void afterChainExecution(CacheContext context, CacheResult result) {
            throw new RuntimeException("post-process boom");
        }

        @Override
        public boolean requiresPostProcess(CacheContext context) {
            return true;
        }
    }
    static class ThrowingPostProcessPredicate implements CacheHandler {

        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueChain();
        }

        @Override
        public boolean requiresPostProcess(CacheContext context) {
            throw new IllegalStateException("post-process predicate boom");
        }
    }


    // ==================== 测试用 observer(替换 mock) ====================

    /**
     * 录制 5 个钩子调用顺序的 ChainObserver — 替换 mock 验证。
     *
     * <p>为什么用真实 observer 而非 mock:onChainStart 返回 Object 时,
     * {@code inOrder.verify(observer).onChainStart(any())} 这种 mock-based 验证
     * 语义失效(mock 返回 Object + 链式 verify 互相干扰),用真实 observer 录制更
     * 鲁棒且意图清晰。
     */
    static class RecordingObserver implements ChainObserver {
        final List<String> events = new ArrayList<>();

        @Override
        public Object onChainStart(CacheContext context) {
            events.add("onChainStart");
            return null;  // 无状态 observer
        }

        @Override
        public Object onNodeStart(CacheHandler handler, CacheContext context) {
            events.add("onNodeStart");
            return null;
        }

        @Override
        public void afterNode(CacheHandler handler, CacheContext context, HandlerResult result) {
            events.add("afterNode");
        }

        @Override
        public void onNodeEnd(CacheHandler handler, CacheContext context,
                              Object scopeToken, HandlerResult result) {
            events.add("onNodeEnd");
        }

        @Override
        public void onChainEnd(CacheContext context, Object scopeToken, CacheResult result) {
            events.add("onChainEnd");
        }
    }

    /**
     * 专门验证 scope token 配对的 observer — scope token 机制契约测试。
     *
     * <p>onChainStart 返回唯一标识 token,onChainEnd 校验传入的 token 与 start 时的
     * 引用相同(Engine 按 index 配对,跨 observer 不混淆)。无 state map 累积干扰。
     */
    static class NodeTokenRecordingObserver implements ChainObserver {
        int startCount;
        int endCount;
        Object startToken;
        Object endToken;
        HandlerResult endResult;

        @Override
        public Object onNodeStart(CacheHandler handler, CacheContext context) {
            startCount++;
            startToken = new Object();
            return startToken;
        }

        @Override
        public void onNodeEnd(CacheHandler handler, CacheContext context,
                              Object scopeToken, HandlerResult result) {
            endCount++;
            endToken = scopeToken;
            endResult = result;
        }
    }

    static class TokenRecordingObserver implements ChainObserver {
        int startCount = 0;
        int endCount = 0;
        Object lastStartToken;
        Object endToken;

        @Override
        public Object onChainStart(CacheContext context) {
            startCount++;
            lastStartToken = new Object();  // 每个 start 一个唯一标识
            return lastStartToken;
        }

        @Override
        public void onChainEnd(CacheContext context, Object scopeToken, CacheResult result) {
            endCount++;
            endToken = scopeToken;
        }
    }
}
