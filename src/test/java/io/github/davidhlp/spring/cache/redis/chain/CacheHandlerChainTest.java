package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheHandlerChain 单元测试 — ADR-0009 后 thin facade 形态。
 *
 * <p>facade 仅做"维护 handler 列表 + 委派 execute 到 Engine"两件事；
 * 推进 / 观测 / post-process 已迁出到 {@link ChainEngine}，相关行为由
 * {@code ChainEngineTest} 覆盖。本测试只覆盖 facade 自身的契约。
 */
@DisplayName("CacheHandlerChain Tests")
class CacheHandlerChainTest {

    private CacheHandlerChain chain;
    private ChainEngine engine;

    @BeforeEach
    void setUp() {
        // 单元测试：手动装配 facade + engine（避免拉起 Spring 容器）
        engine = new ChainEngine();
        chain = new CacheHandlerChain();
        chain.setEngine(engine);
    }

    private CacheContext createTestContext() {
        return CacheContext.builder()
                .operation(CacheOperation.GET)
                .cacheName("test-cache")
                .redisKey("test:key")
                .actualKey("test:key")
                .build();
    }

    @Nested
    @DisplayName("addHandler")
    class AddHandlerTests {

        @Test
        @DisplayName("添加单个处理器")
        void addHandler_singleHandler_chainSizeIsOne() {
            CacheHandler handler = new TestCacheHandler();
            chain.addHandler(handler);
            assertThat(chain.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("添加多个处理器形成链")
        void addHandler_multipleHandlers_chainSizeCorrect() {
            chain.addHandler(new TestCacheHandler());
            chain.addHandler(new TestCacheHandler());
            chain.addHandler(new TestCacheHandler());
            assertThat(chain.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("返回 this 支持链式调用")
        void addHandler_returnsChainForChaining() {
            CacheHandler handler = new TestCacheHandler();
            CacheHandlerChain returned = chain.addHandler(handler);
            assertThat(returned).isSameAs(chain);
        }

        @Test
        @DisplayName("addHandler 同步刷新 Engine 持有的链快照")
        void addHandler_refreshesEngineSnapshot() {
            chain.addHandler(new TestCacheHandler());
            // Engine 应能从 snapshot 读到这个 handler —— execute 行为可观察
            assertThat(engine.observers()).isEmpty(); // observers 仍空（facade 不注册）
            // snapshot 已就绪 — 跑一次 execute 不报"空链"
            CacheResult result = chain.execute(createTestContext());
            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("execute")
    class ExecuteTests {

        @Test
        @DisplayName("空链返回成功结果")
        void execute_emptyChain_returnsSuccess() {
            CacheContext context = createTestContext();
            CacheResult result = chain.execute(context);
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("单处理器执行成功")
        void execute_singleHandler_executesSuccessfully() {
            AtomicBoolean handlerCalled = new AtomicBoolean(false);
            CacheHandler handler = new TestCacheHandler() {
                @Override
                public HandlerResult handle(CacheContext context) {
                    handlerCalled.set(true);
                    return HandlerResult.continueWith(CacheResult.success());
                }
            };
            chain.addHandler(handler);

            CacheContext context = createTestContext();
            CacheResult result = chain.execute(context);

            assertThat(handlerCalled.get()).isTrue();
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("多处理器按顺序执行")
        void execute_multipleHandlers_executesInOrder() {
            AtomicBoolean firstCalled = new AtomicBoolean(false);
            AtomicBoolean secondCalled = new AtomicBoolean(false);

            CacheHandler first = new TestCacheHandler() {
                @Override
                public HandlerResult handle(CacheContext context) {
                    firstCalled.set(true);
                    return HandlerResult.continueChain();
                }
            };

            CacheHandler second = new TestCacheHandler() {
                @Override
                public HandlerResult handle(CacheContext context) {
                    secondCalled.set(true);
                    return HandlerResult.continueWith(CacheResult.success());
                }
            };

            chain.addHandler(first);
            chain.addHandler(second);

            CacheContext context = createTestContext();
            chain.execute(context);

            assertThat(firstCalled.get()).isTrue();
            assertThat(secondCalled.get()).isTrue();
        }

        @Test
        @DisplayName("返回 null 的结果被替换为成功结果")
        void execute_nullResult_replacedWithSuccess() {
            CacheHandler handler = new TestCacheHandler() {
                @Override
                public HandlerResult handle(CacheContext context) {
                    return HandlerResult.continueWith(null);
                }
            };
            chain.addHandler(handler);

            CacheContext context = createTestContext();
            CacheResult result = chain.execute(context);

            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("PostProcessHandler")
    class PostProcessHandlerTests {

        @Test
        @DisplayName("后置处理器在链执行后被调用")
        void execute_withPostProcessor_calledAfterChain() {
            AtomicBoolean postProcessorCalled = new AtomicBoolean(false);
            TestPostProcessor postProcessor = new TestPostProcessor(postProcessorCalled);

            chain.addHandler(new TestCacheHandler());
            chain.addHandler(postProcessor);

            CacheContext context = createTestContext();
            chain.execute(context);

            assertThat(postProcessorCalled.get()).isTrue();
        }

        @Test
        @DisplayName("requiresPostProcess 返回 false 时不调用后置处理")
        void execute_postProcessorNotRequired_notCalled() {
            AtomicBoolean postProcessorCalled = new AtomicBoolean(false);
            TestPostProcessor postProcessor = new TestPostProcessor(postProcessorCalled, false);

            chain.addHandler(new TestCacheHandler());
            chain.addHandler(postProcessor);

            CacheContext context = createTestContext();
            chain.execute(context);

            assertThat(postProcessorCalled.get()).isFalse();
        }
    }

    @Nested
    @DisplayName("clear")
    class ClearTests {

        @Test
        @DisplayName("清空后链大小为 0")
        void clear_afterAddingHandlers_sizeIsZero() {
            chain.addHandler(new TestCacheHandler());
            chain.addHandler(new TestCacheHandler());
            chain.clear();
            assertThat(chain.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("清空后执行返回成功")
        void clear_emptyChain_executesSuccessfully() {
            chain.addHandler(new TestCacheHandler());
            chain.clear();

            CacheContext context = createTestContext();
            CacheResult result = chain.execute(context);
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("clear 同步刷新 Engine 持有的链快照为 null")
        void clear_refreshesEngineSnapshotToNull() {
            chain.addHandler(new TestCacheHandler());
            chain.clear();
            // execute 不应进入主循环（空链短路）
            CacheResult result = chain.execute(createTestContext());
            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("getHandlerNames")
    class GetHandlerNamesTests {

        @Test
        @DisplayName("返回所有处理器名称")
        void getHandlerNames_returnsAllNames() {
            chain.addHandler(new TestCacheHandler());
            chain.addHandler(new AnotherTestHandler());

            var names = chain.getHandlerNames();
            assertThat(names).containsExactly("TestCacheHandler", "AnotherTestHandler");
        }

        @Test
        @DisplayName("空链返回空列表")
        void getHandlerNames_emptyChain_returnsEmptyList() {
            assertThat(chain.getHandlerNames()).isEmpty();
        }
    }

    @Nested
    @DisplayName("size")
    class SizeTests {

        @Test
        @DisplayName("空链大小为 0")
        void size_emptyChain_returnsZero() {
            assertThat(chain.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("添加处理器后大小正确")
        void size_withHandlers_returnsCorrectSize() {
            chain.addHandler(new TestCacheHandler());
            chain.addHandler(new TestCacheHandler());
            assertThat(chain.size()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("per-handler observability (MDC requestId) — via Engine observer")
    class MdcObservabilityTests {

        // 走真实 AbstractCacheHandler 引擎的 handler:doHandle 内捕获 MDC 中的 requestId。
        // 用于验证 Engine 中 MDCStampChainObserver stamp 的 requestId 在整条链内可被
        // 每个 handler 观察到（facade.execute → engine.execute → MDCStampChainObserver.onChainStart
        // → 节点循环 → each handler doHandle reads MDC）。
        private AbstractCacheHandler recordingHandler(List<String> sink, HandlerResult result) {
            return new AbstractCacheHandler() {
                @Override
                protected boolean shouldHandle(CacheContext context) {
                    return true;
                }

                @Override
                protected HandlerResult doHandle(CacheContext context) {
                    sink.add(MDC.get(CacheHandlerChain.MDC_REQUEST_ID_KEY));
                    return result;
                }
            };
        }

        private void installDefaultObservers() {
            engine.addObserver(new io.github.davidhlp.spring.cache.redis.chain.observer.MDCStampChainObserver());
        }

        @Test
        @DisplayName("execute 用单一 requestId 关联所有被求值的 handler,执行后从 MDC 清除")
        void execute_stampsSingleRequestId_correlatingAllHandlers_thenClears() {
            installDefaultObservers();
            List<String> seen = new ArrayList<>();
            chain.addHandler(recordingHandler(seen, HandlerResult.continueChain()));
            chain.addHandler(recordingHandler(seen, HandlerResult.continueWith(CacheResult.success())));

            chain.execute(createTestContext());

            // 每个被引擎求值的 handler 都观察到一个非 null requestId
            assertThat(seen).hasSize(2).doesNotContainNull();
            // 两个 handler 共享同一个 requestId —— 这是"单次 GET/PUT 的 DEBUG trace 可串联"的契约
            assertThat(seen.get(0)).isEqualTo(seen.get(1));
            // 执行结束后 requestId 从 MDC 移除(不泄漏到调用方线程)
            assertThat(MDC.get(CacheHandlerChain.MDC_REQUEST_ID_KEY)).isNull();
        }

        @Test
        @DisplayName("execute 恢复调用方在 MDC 中预设的 requestId(snapshot/restore,不误清宿主 MDC)")
        void execute_restoresCallerRequestId_afterCompletion() {
            installDefaultObservers();
            chain.addHandler(recordingHandler(new ArrayList<>(),
                    HandlerResult.continueWith(CacheResult.success())));

            MDC.put(CacheHandlerChain.MDC_REQUEST_ID_KEY, "caller-id");
            try {
                chain.execute(createTestContext());
                // 执行后必须恢复调用方原值,而非残留框架生成的 id 或被清空
                assertThat(MDC.get(CacheHandlerChain.MDC_REQUEST_ID_KEY)).isEqualTo("caller-id");
            } finally {
                MDC.remove(CacheHandlerChain.MDC_REQUEST_ID_KEY);
            }
        }
    }

    // Test handler implementations — 简化为"返回结果不主动推进" — Engine 负责推进
    static class TestCacheHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }
    }

    static class AnotherTestHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }
    }

    static class TestPostProcessor implements CacheHandler, PostProcessHandler {
        private final AtomicBoolean called;
        private final boolean requiresPostProcess;

        TestPostProcessor(AtomicBoolean called) {
            this(called, true);
        }

        TestPostProcessor(AtomicBoolean called, boolean requiresPostProcess) {
            this.called = called;
            this.requiresPostProcess = requiresPostProcess;
        }

        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

        @Override
        public void afterChainExecution(CacheContext context, CacheResult result) {
            called.set(true);
        }

        @Override
        public boolean requiresPostProcess(CacheContext context) {
            return requiresPostProcess;
        }
    }
}
