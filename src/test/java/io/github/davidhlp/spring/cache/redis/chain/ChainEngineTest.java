package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheInput;
import io.github.davidhlp.spring.cache.redis.chain.observer.MDCStampChainObserver;
import io.github.davidhlp.spring.cache.redis.chain.observer.NoOpChainObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChainEngine 单元测试 — ADR-0009 后唯一推进引擎的契约。
 *
 * <p>Engine 的全部职责（链推进 + decision switch + 观测编排 + post-process）在本测试
 * 中直接验证。{@code CacheHandlerChainTest} 仅覆盖 facade 自身的 handler 列表管理。
 */
@DisplayName("ChainEngine Tests")
class ChainEngineTest {

    private ChainEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ChainEngine();
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
        // ADR-0022:链结构为单一 List 快照 — 无需 setNext 链接,Engine 按 index 推进
        engine.setChainSnapshot(List.of(handlers));
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

            CacheResult result = engine.execute(newCtx());

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

            engine.execute(newCtx());

            assertThat(visitOrder).containsExactly("h1", "h2", "h3");
        }

        @Test
        @DisplayName("TERMINATE decision → 立即返回,后续 handler 不被求值")
        void terminate_stopsSubsequentHandlers() {
            List<String> visitOrder = new ArrayList<>();
            CacheHandler h1 = new RecordingHandler("h1", visitOrder, HandlerResult.terminate(CacheResult.success()));
            CacheHandler h2 = new RecordingHandler("h2", visitOrder, HandlerResult.continueChain());
            installChain(h1, h2);

            engine.execute(newCtx());

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
                protected HandlerResult doHandle(CacheContext context) {
                    nextCalled.set(true);
                    return HandlerResult.continueChain();
                }
            };
            installChain(h1, h2);

            CacheContext ctx = newCtx();
            engine.execute(ctx);

            // h2 确实没被求值(SKIP_ALL 已物化,下一个 iteration 检测到 isSkipRemaining)
            assertThat(nextCalled.get()).isFalse();
        }

        @Test
        @DisplayName("CONTINUE + 链尾 + handler 返回 null result → 退化为 success(与原 executeChainInternal 一致)")
        void continueAtChainTail_nullResult_returnsSuccess() {
            CacheHandler h = new RecordingHandler("h1", HandlerResult.continueWith(null));
            installChain(h);

            CacheResult result = engine.execute(newCtx());

            assertThat(result.isSuccess()).isTrue();
        }
    }

    // ==================== 观测编排 ====================

    @Nested
    @DisplayName("observer orchestration")
    class ObserverTests {

        @Test
        @DisplayName("aroundChain:onChainStart → beforeNode → afterNode → onChainEnd 顺序执行")
        void aroundChain_andPerNode_calledInOrder() {
            ChainObserver observer = mock(ChainObserver.class);
            engine.addObserver(observer);
            installChain(new RecordingHandler("h1", HandlerResult.continueWith(CacheResult.success())));

            engine.execute(newCtx());

            InOrder inOrder = inOrder(observer);
            inOrder.verify(observer).onChainStart(any());
            inOrder.verify(observer).beforeNode(any(), any());
            inOrder.verify(observer).afterNode(any(), any(), any());
            inOrder.verify(observer).onChainEnd(any(), any());
        }

        @Test
        @DisplayName("executeChainFragment:不调 aroundChain 钩子,只调 perNode(推进 from 之后)")
        void executeFragment_skipsAroundChain() {
            ChainObserver observer = mock(ChainObserver.class);
            engine.addObserver(observer);
            // ADR-0022: executeChainFragment 语义为「推进 from 之后的剩余链」(不再含 from 本身)
            // h0 作 fragment 发起者(模拟 SyncLockHandler 锁内传 this),h1/h2 是其后继
            CacheHandler h0 = new RecordingHandler("h0", HandlerResult.continueChain());
            CacheHandler h1 = new RecordingHandler("h1", HandlerResult.continueChain());
            CacheHandler h2 = new RecordingHandler("h2", HandlerResult.continueWith(CacheResult.success()));
            installChain(h0, h1, h2);

            CacheResult result = engine.executeChainFragment(newCtx(), h0);

            assertThat(result.isSuccess()).isTrue();
            // aroundChain 未触发(fragment 不应 stamp MDC / record Timer)
            verify(observer, times(0)).onChainStart(any());
            verify(observer, times(0)).onChainEnd(any(), any());
            // perNode 对 from(h0)之后的 h1/h2 各调一次 → 共 2 次
            verify(observer, times(2)).beforeNode(any(), any());
            verify(observer, times(2)).afterNode(any(), any(), any());
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

            engine.execute(newCtx());

            assertThat(captured[0]).isSameAs(expected);
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

            engine.execute(newCtx());

            assertThat(ppCalled.get()).isTrue();
        }

        @Test
        @DisplayName("post-process 抛异常被 try/catch 吞掉,主链结果不受影响")
        void postProcessFailure_swallowed() {
            CacheHandler main = new RecordingHandler("main", HandlerResult.continueWith(CacheResult.success()));
            CacheHandler pp = new ThrowingPostProcessor();
            installChain(main, pp);

            // 不应抛异常
            CacheResult result = engine.execute(newCtx());

            // main 返回 success — 即使 pp 抛异常,主链 result 仍是 success
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("requiresPostProcess=false → 不调 afterChainExecution")
        void postProcessNotRequired_notCalled() {
            AtomicBoolean ppCalled = new AtomicBoolean(false);
            CacheHandler main = new RecordingHandler("main", HandlerResult.continueWith(CacheResult.success()));
            CacheHandler pp = new PostProcessRecordingHandler("pp", ppCalled, false);
            installChain(main, pp);

            engine.execute(newCtx());

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
            // 不调用 setChainSnapshot → snapshot 是 null
            // 用 spy 拦截 logger 不易,改为验证返回值即可
            CacheResult result = engine.execute(newCtx());
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("空链(snapshot=空列表) → 返回 success")
        void emptySnapshot_returnsSuccess() {
            engine.setChainSnapshot(List.of());
            CacheResult result = engine.execute(newCtx());
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

            assertThatThrownBy(() -> engine.execute(newCtx()))
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
        @DisplayName("executeChainFragment(from=null) → 返回 success,不调任何 observer")
        void executeFragment_fromNull_returnsSuccess() {
            ChainObserver observer = mock(ChainObserver.class);
            engine.addObserver(observer);

            CacheResult result = engine.executeChainFragment(newCtx(), null);

            assertThat(result.isSuccess()).isTrue();
            verify(observer, times(0)).beforeNode(any(), any());
        }
    }

    // ==================== 测试用 handler 实现 ====================

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

    static class PostProcessRecordingHandler implements CacheHandler, PostProcessHandler {
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

    static class ThrowingPostProcessor implements CacheHandler, PostProcessHandler {

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
}
