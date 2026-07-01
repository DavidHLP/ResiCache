package io.github.davidhlp.spring.cache.redis.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.cache.interceptor.CacheOperation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnnotationChainEngine} — ADR-0013 唯一推进引擎的契约.
 *
 * <p>Engine 全部职责(链推进 + per-handler 失败隔离 + 结果收集 + 观测编排)
 * 在本测试中直接验证。{@code AnnotationHandlerTest} 仅覆盖抽象节点的两个钩子契约。
 */
@DisplayName("AnnotationChainEngine Tests")
class AnnotationChainEngineTest {

    private AnnotationChainEngine engine;
    private Method noAnnotationMethod;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        noAnnotationMethod = TestClass.class.getMethod("noAnnotation");
    }

    private static class TestClass {
        public void noAnnotation() {}
    }

    /**
     * Test handler — canHandle 命中(shouldHandle=true)时返回 doHandleResult。
     */
    private static class TestHandler extends AnnotationHandler {
        boolean shouldHandle = true;
        List<CacheOperation> doHandleResult = Collections.emptyList();
        final List<String> visitLog = new ArrayList<>();
        final String name;

        TestHandler(String name) {
            this.name = name;
        }

        @Override
        protected boolean canHandle(Method method) {
            visitLog.add(name + ":canHandle");
            return shouldHandle;
        }

        @Override
        protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
            visitLog.add(name + ":doHandle");
            return doHandleResult;
        }
    }

    // ==================== 推进协议 ====================

    @Nested
    @DisplayName("chain advance")
    class ChainAdvanceTests {

        @Test
        @DisplayName("单 handler canHandle 命中 → doHandle 被调用")
        void singleHandler_canHandleHit_doHandleCalled() {
            TestHandler h = new TestHandler("h1");
            h.doHandleResult = List.of(mock(CacheOperation.class));
            engine = new AnnotationChainEngine(List.of(h));

            List<CacheOperation> result = engine.execute(noAnnotationMethod, new Object(), new Object[0]);

            assertThat(result).hasSize(1);
            assertThat(h.visitLog).containsExactly("h1:canHandle", "h1:doHandle");
        }

        @Test
        @DisplayName("单 handler canHandle 未命中 → doHandle 不被调用,结果为空")
        void singleHandler_canHandleMiss_doHandleSkipped() {
            TestHandler h = new TestHandler("h1");
            h.shouldHandle = false;
            engine = new AnnotationChainEngine(List.of(h));

            List<CacheOperation> result = engine.execute(noAnnotationMethod, new Object(), new Object[0]);

            assertThat(result).isEmpty();
            assertThat(h.visitLog).containsExactly("h1:canHandle");
        }

        @Test
        @DisplayName("多 handler 按注入顺序遍历,每个 handler 独立求值")
        void multipleHandlers_advanceInOrder() {
            TestHandler h1 = new TestHandler("h1");
            h1.doHandleResult = List.of(mock(CacheOperation.class));
            TestHandler h2 = new TestHandler("h2");
            TestHandler h3 = new TestHandler("h3");
            h3.doHandleResult = List.of(mock(CacheOperation.class), mock(CacheOperation.class));
            engine = new AnnotationChainEngine(List.of(h1, h2, h3));

            List<CacheOperation> result = engine.execute(noAnnotationMethod, new Object(), new Object[0]);

            assertThat(result).hasSize(3); // h1:1 + h3:2
            assertThat(h1.visitLog).containsExactly("h1:canHandle", "h1:doHandle");
            assertThat(h2.visitLog).containsExactly("h2:canHandle", "h2:doHandle"); // canHandle=true → doHandle 必被调用(空结果也调用,保持契约简单)
            assertThat(h3.visitLog).containsExactly("h3:canHandle", "h3:doHandle");
        }

        @Test
        @DisplayName("canHandle 未命中 handler 仍被遍历(不短路),但 doHandle 不被调用")
        void multipleHandlers_canHandleMiss_continuesTraversal() {
            TestHandler h1 = new TestHandler("h1");
            h1.shouldHandle = false;
            TestHandler h2 = new TestHandler("h2");
            h2.doHandleResult = List.of(mock(CacheOperation.class));
            engine = new AnnotationChainEngine(List.of(h1, h2));

            List<CacheOperation> result = engine.execute(noAnnotationMethod, new Object(), new Object[0]);

            assertThat(result).hasSize(1);
            assertThat(h1.visitLog).containsExactly("h1:canHandle"); // 只调 canHandle
            assertThat(h2.visitLog).containsExactly("h2:canHandle", "h2:doHandle");
        }
    }

    // ==================== 失败隔离 ====================

    @Nested
    @DisplayName("failure isolation")
    class FailureIsolationTests {

        @Test
        @DisplayName("单个 handler doHandle 抛异常 → 该 handler 失败,剩余 handler 继续")
        void singleHandlerException_otherHandlersContinue() {
            TestHandler h1 = new TestHandler("h1");
            h1.doHandleResult = List.of(mock(CacheOperation.class));
            TestHandler h2 = new TestHandler("h2") {
                @Override
                protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
                    throw new RuntimeException("h2 boom");
                }
            };
            TestHandler h3 = new TestHandler("h3");
            h3.doHandleResult = List.of(mock(CacheOperation.class));
            engine = new AnnotationChainEngine(List.of(h1, h2, h3));

            List<CacheOperation> result = engine.execute(noAnnotationMethod, new Object(), new Object[0]);

            // h1 贡献 1, h2 失败(无贡献), h3 贡献 1 — 总 2
            assertThat(result).hasSize(2);
            assertThat(h3.visitLog).containsExactly("h3:canHandle", "h3:doHandle");
        }

        @Test
        @DisplayName("handler doHandle 返回 null → 视为空 list(不抛 NPE)")
        void handlerReturnsNull_treatedAsEmpty() {
            TestHandler h = new TestHandler("h1") {
                @Override
                protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
                    return null;
                }
            };
            engine = new AnnotationChainEngine(List.of(h));

            List<CacheOperation> result = engine.execute(noAnnotationMethod, new Object(), new Object[0]);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("canHandle 抛异常 → Engine 自身不处理(子类的锅) — 隔离仅覆盖 doHandle")
        void canHandleException_propagates() {
            TestHandler h = new TestHandler("h1") {
                @Override
                protected boolean canHandle(Method method) {
                    throw new RuntimeException("canHandle boom");
                }
            };
            engine = new AnnotationChainEngine(List.of(h));

            assertThatThrownBy(() -> engine.execute(noAnnotationMethod, new Object(), new Object[0]))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("canHandle boom");
        }
    }

    // ==================== 观测编排 ====================

    @Nested
    @DisplayName("observer orchestration")
    class ObserverTests {

        @Test
        @DisplayName("aroundChain:onChainStart → 所有 handler 求值 → onChainEnd 顺序执行")
        void aroundChain_calledInOrder() {
            AnnotationChainObserver observer = mock(AnnotationChainObserver.class);
            TestHandler h1 = new TestHandler("h1");
            h1.doHandleResult = List.of(mock(CacheOperation.class));
            engine = new AnnotationChainEngine(List.of(h1));
            engine.addObserver(observer);

            engine.execute(noAnnotationMethod, new Object(), new Object[0]);

            InOrder inOrder = inOrder(observer);
            inOrder.verify(observer).onChainStart(eq(noAnnotationMethod), any(), any());
            inOrder.verify(observer).onChainEnd(eq(noAnnotationMethod), any(), any(), any());
        }

        @Test
        @DisplayName("onChainEnd 收到 handler.doHandle 的真实结果(不可变 list)")
        void onChainEndReceivesResult() {
            List<CacheOperation>[] captured = new List[1];
            AnnotationChainObserver capture = new AnnotationChainObserver() { @Override public void onChainEnd(Method method, Object target, Object[] args, List<CacheOperation> result) { captured[0] = result; } };
            TestHandler h1 = new TestHandler("h1");
            h1.doHandleResult = List.of(mock(CacheOperation.class));
            engine = new AnnotationChainEngine(List.of(h1));
            engine.addObserver(capture);

            engine.execute(noAnnotationMethod, new Object(), new Object[0]);

            assertThat(captured[0]).hasSize(1);
        }

        @Test
        @DisplayName("observer 异常不阻塞主链(与 ChainEngine.execute 行为一致)")
        void observerException_doesNotBlockChain() {
            AnnotationChainObserver faulty = mock(AnnotationChainObserver.class);
            org.mockito.Mockito.doThrow(new RuntimeException("observer boom"))
                    .when(faulty).onChainStart(any(), any(), any());
            TestHandler h1 = new TestHandler("h1");
            h1.doHandleResult = List.of(mock(CacheOperation.class));
            engine = new AnnotationChainEngine(List.of(h1));
            engine.addObserver(faulty);

            // observer 异常被 Engine 捕获并打 ERROR,主链继续
            List<CacheOperation> result = engine.execute(noAnnotationMethod, new Object(), new Object[0]);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("onChainEnd 在异常路径下也保证触发(try/finally 守护)")
        void onChainEnd_triggeredOnExceptionPath() {
            AtomicBoolean onChainEndCalled = new AtomicBoolean(false);
            AnnotationChainObserver capture = new AnnotationChainObserver() { @Override public void onChainEnd(Method method, Object target, Object[] args, List<CacheOperation> result) { onChainEndCalled.set(true); } };
            TestHandler h1 = new TestHandler("h1") {
                @Override
                protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
                    throw new RuntimeException("h1 boom");
                }
            };
            engine = new AnnotationChainEngine(List.of(h1));
            engine.addObserver(capture);

            // h1 抛异常被 Engine per-handler try/catch 捕获,onChainEnd 仍触发
            engine.execute(noAnnotationMethod, new Object(), new Object[0]);

            assertThat(onChainEndCalled.get()).isTrue();
        }
    }

    // ==================== API surface ====================

    @Nested
    @DisplayName("API surface")
    class ApiSurfaceTests {

        @Test
        @DisplayName("execute method 参数校验:method 为 null 抛 IllegalArgumentException")
        void execute_nullMethod_throws() {
            engine = new AnnotationChainEngine(Collections.emptyList());

            assertThatThrownBy(() -> engine.execute(null, new Object(), new Object[0]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("method");
        }

        @Test
        @DisplayName("execute:args 为 null 时自动兜底为空数组(无参方法支持)")
        void execute_nullArgs_defaultsToEmpty() {
            TestHandler h = new TestHandler("h1");
            h.doHandleResult = List.of(mock(CacheOperation.class));
            engine = new AnnotationChainEngine(List.of(h));

            // args=null 兜底 — 不抛 NPE
            List<CacheOperation> result = engine.execute(noAnnotationMethod, new Object(), null);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("addObserver(null) 抛 IllegalArgumentException")
        void addObserver_null_throws() {
            engine = new AnnotationChainEngine(Collections.emptyList());

            assertThatThrownBy(() -> engine.addObserver(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("observer");
        }

        @Test
        @DisplayName("observers() 返回不可变快照")
        void observers_returnsImmutableSnapshot() {
            engine = new AnnotationChainEngine(Collections.emptyList());
            engine.addObserver(new AnnotationChainObserver() {});

            List<AnnotationChainObserver> snapshot = engine.observers();

            assertThatThrownBy(() -> snapshot.add(mock(AnnotationChainObserver.class)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("execute 返回的 list 是不可变(防止 handler 误改)")
        void execute_returnsUnmodifiableList() {
            TestHandler h = new TestHandler("h1");
            h.doHandleResult = new ArrayList<>(List.of(mock(CacheOperation.class)));
            engine = new AnnotationChainEngine(List.of(h));

            List<CacheOperation> result = engine.execute(noAnnotationMethod, new Object(), new Object[0]);

            assertThatThrownBy(() -> result.add(mock(CacheOperation.class)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("空 handler 列表 → 返回空 list,无 NPE")
        void emptyHandlerList_returnsEmpty() {
            engine = new AnnotationChainEngine(Collections.emptyList());

            List<CacheOperation> result = engine.execute(noAnnotationMethod, new Object(), new Object[0]);

            assertThat(result).isEmpty();
        }
    }
}
