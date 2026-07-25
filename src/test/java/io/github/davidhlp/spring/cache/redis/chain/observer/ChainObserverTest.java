package io.github.davidhlp.spring.cache.redis.chain.observer;

import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChain;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.observer.ChainObserver;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheInput;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChainObserver 实现测试 — ADR-0009 引入的 4 个标准 observer 的契约。
 *
 * <p>每个 observer 独立测试其特定钩子的行为（MDC stamp / DEBUG log / Timer / fired counter），
 * 验证与 Engine 解耦后能正确实现单一职责。空 observer 列表的"什么都不做"语义由
 * {@link io.github.davidhlp.spring.cache.redis.chain.ChainEngine} 的 ObserverRegistry
 * 空列表分支承载（ADR-0039 删除 NoOpChainObserver 后,空观测无需占位单例）。
 */
@DisplayName("ChainObserver Implementations")
class ChainObserverTest {

    private CacheContext ctx;
    private CacheHandler handler;

    @BeforeEach
    void setUp() {
        ctx = CacheContext.of(CacheInput.builder()
                .operation(CacheOperation.GET)
                .cacheName("test-cache")
                .redisKey("test:key")
                .actualKey("test:key")
                .build());
        handler = new CacheHandler() {
            @Override public HandlerResult handle(CacheContext c) { return HandlerResult.continueChain(); }
        };
    }

    @Nested
    @DisplayName("MDCStampChainObserver")
    class MdcStampTests {

        @Test
        @DisplayName("onChainStart 写入新 requestId 到 MDC,onChainEnd 恢复调用方原值")
        void startAndEnd_roundtrip() {
            ChainObserver observer = new MDCStampChainObserver();

            MDC.put(CacheHandlerChain.MDC_REQUEST_ID_KEY, "caller-id");
            try {
                // ADR-0061:onChainStart 返回 scope token(MdcScope record),Engine 配对回传到
                // onChainEnd。test 直接持有 token 模拟 Engine 协议
                Object scopeToken = observer.onChainStart(ctx);
                String stamped = MDC.get(CacheHandlerChain.MDC_REQUEST_ID_KEY);
                assertThat(stamped).isNotNull().isNotEqualTo("caller-id");

                observer.onChainEnd(ctx, scopeToken, CacheResult.success());
                // 恢复调用方原值
                assertThat(MDC.get(CacheHandlerChain.MDC_REQUEST_ID_KEY)).isEqualTo("caller-id");
            } finally {
                MDC.remove(CacheHandlerChain.MDC_REQUEST_ID_KEY);
            }
        }

        @Test
        @DisplayName("调用方未预设 MDC → onChainStart 写入,onChainEnd 移除(不残留)")
        void noCallerMdc_thenStartWritesEndRemoves() {
            ChainObserver observer = new MDCStampChainObserver();
            assertThat(MDC.get(CacheHandlerChain.MDC_REQUEST_ID_KEY)).isNull();

            Object scopeToken = observer.onChainStart(ctx);
            assertThat(MDC.get(CacheHandlerChain.MDC_REQUEST_ID_KEY)).isNotNull();

            observer.onChainEnd(ctx, scopeToken, CacheResult.success());
            assertThat(MDC.get(CacheHandlerChain.MDC_REQUEST_ID_KEY)).isNull();
        }

        @Test
        @DisplayName("两次 start/end 配对,每次生成不同的 requestId")
        void multipleStartEnd_generateDifferentIds() {
            ChainObserver observer = new MDCStampChainObserver();
            String first, second;
            Object token1 = observer.onChainStart(ctx);
            first = MDC.get(CacheHandlerChain.MDC_REQUEST_ID_KEY);
            observer.onChainEnd(ctx, token1, CacheResult.success());
            Object token2 = observer.onChainStart(ctx);
            second = MDC.get(CacheHandlerChain.MDC_REQUEST_ID_KEY);
            observer.onChainEnd(ctx, token2, CacheResult.success());

            assertThat(first).isNotEqualTo(second);
        }
    }

    @Nested
    @DisplayName("ChainTimerChainObserver")
    class TimerTests {

        @Test
        @DisplayName("registry 缺失时节点计时 no-op")
        void nullRegistry_noOp() {
            ChainObserver observer = new ChainTimerChainObserver(null);

            Object scopeToken = observer.onNodeStart(handler, ctx);

            assertThat(scopeToken).isNull();
            observer.onNodeEnd(handler, ctx, scopeToken, HandlerResult.continueChain());
        }

        @Test
        @DisplayName("成功节点按 handler、decision、cacheName 记录一次 Timer")
        void successfulNode_recordsBoundedTags() {
            MeterRegistry registry = new SimpleMeterRegistry();
            ChainObserver observer = new ChainTimerChainObserver(registry);
            CacheHandler namedHandler = new ContinueHandler();

            Object scopeToken = observer.onNodeStart(namedHandler, ctx);
            observer.onNodeEnd(namedHandler, ctx, scopeToken,
                    HandlerResult.terminate(CacheResult.success()));

            Timer timer = registry.find(ChainTimerChainObserver.METRIC_NAME)
                    .tag("handler", "ContinueHandler")
                    .tag("decision", "TERMINATE")
                    .tag("cacheName", "test-cache")
                    .timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1L);
            assertThat(timer.getId().getTags())
                    .extracting(tag -> tag.getKey())
                    .containsExactlyInAnyOrder("handler", "decision", "cacheName");
        }

        @Test
        @DisplayName("动态 redisKey 不增加 meter cardinality")
        void changingRedisKey_doesNotCreateMeters() {
            MeterRegistry registry = new SimpleMeterRegistry();
            ChainObserver observer = new ChainTimerChainObserver(registry);
            CacheHandler namedHandler = new ContinueHandler();

            for (int i = 0; i < 20; i++) {
                CacheContext dynamicContext = context("test-cache", "user:" + i);
                Object scopeToken = observer.onNodeStart(namedHandler, dynamicContext);
                observer.onNodeEnd(namedHandler, dynamicContext, scopeToken,
                        HandlerResult.continueChain());
            }

            assertThat(registry.find(ChainTimerChainObserver.METRIC_NAME).timers())
                    .singleElement()
                    .satisfies(timer -> {
                        assertThat(timer.count()).isEqualTo(20L);
                        assertThat(timer.getId().getTag("redisKey")).isNull();
                    });
        }

        @Test
        @DisplayName("三种 decision 与 cacheName 形成明确有限的 meter 组合")
        void decisionAndCacheName_partitionMeters() {
            MeterRegistry registry = new SimpleMeterRegistry();
            ChainObserver observer = new ChainTimerChainObserver(registry);
            CacheHandler namedHandler = new ContinueHandler();
            HandlerResult[] results = {
                    HandlerResult.continueChain(),
                    HandlerResult.skipAll(),
                    HandlerResult.terminate()
            };

            for (String cacheName : new String[]{"catalog", "sessions"}) {
                for (HandlerResult result : results) {
                    CacheContext context = context(cacheName, "same-key");
                    Object scopeToken = observer.onNodeStart(namedHandler, context);
                    observer.onNodeEnd(namedHandler, context, scopeToken, result);
                }
            }

            assertThat(registry.find(ChainTimerChainObserver.METRIC_NAME).timers())
                    .hasSize(6)
                    .allSatisfy(timer -> {
                        assertThat(timer.count()).isEqualTo(1L);
                        assertThat(timer.getId().getTag("decision"))
                                .isIn("CONTINUE", "SKIP_ALL", "TERMINATE");
                        assertThat(timer.getId().getTag("cacheName"))
                                .isIn("catalog", "sessions");
                    });
        }

        @Test
        @DisplayName("异常节点只回收 token，不记录虚假 decision")
        void nullResult_doesNotRecordTimer() {
            MeterRegistry registry = new SimpleMeterRegistry();
            ChainObserver observer = new ChainTimerChainObserver(registry);
            CacheHandler namedHandler = new ContinueHandler();

            Object scopeToken = observer.onNodeStart(namedHandler, ctx);
            observer.onNodeEnd(namedHandler, ctx, scopeToken, null);

            assertThat(registry.find(ChainTimerChainObserver.METRIC_NAME).timers()).isEmpty();
        }

        private CacheContext context(String cacheName, String redisKey) {
            return CacheContext.of(CacheInput.builder()
                    .operation(CacheOperation.GET)
                    .cacheName(cacheName)
                    .redisKey(redisKey)
                    .actualKey(redisKey)
                    .build());
        }

        private final class ContinueHandler implements CacheHandler {
            @Override
            public HandlerResult handle(CacheContext context) {
                return HandlerResult.continueChain();
            }
        }
    }

    @Nested
    @DisplayName("FiredCounterChainObserver")
    class FiredCounterTests {

        @Test
        @DisplayName("registry 缺失 → afterNode 自增调用为 no-op,不抛异常")
        void nullRegistry_noOp() {
            ChainObserver observer = new FiredCounterChainObserver(null);
            observer.afterNode(handler, ctx, HandlerResult.continueChain());
            // 无异常即可
        }

        @Test
        @DisplayName("afterNode 自增 handler 类型对应 counter")
        void afterNode_incrementsPerHandlerType() {
            MeterRegistry registry = new SimpleMeterRegistry();
            ChainObserver observer = new FiredCounterChainObserver(registry);

            for (int i = 0; i < 3; i++) {
                observer.afterNode(handler, ctx, HandlerResult.continueChain());
            }

            Counter counter = registry.find("resicache.handler.fired")
                    .tag("handler", handler.getClass().getSimpleName())
                    .counter();
            assertThat(counter).isNotNull();
            assertThat((double) counter.count()).isEqualTo(3.0);
        }
    }
}
