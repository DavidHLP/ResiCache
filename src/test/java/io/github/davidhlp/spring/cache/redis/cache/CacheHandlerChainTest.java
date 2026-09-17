package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CacheHandlerChain 单元测试 — thin facade 形态。
 *
 * <p>facade 仅做"维护 handler 列表 + 委派 execute 到 Engine"两件事；
 * 推进 / 观测 / post-process 由 {@link ChainEngine} 承载,相关行为由
 * {@code ChainEngineTest} 覆盖。本测试只覆盖 facade 自身的契约。
 */
@DisplayName("CacheHandlerChain Tests")
class CacheHandlerChainTest {

    private CacheHandlerChain chain;
    private ChainEngine engine;

    @BeforeEach
    void setUp() {
        engine = mock(ChainEngine.class);
        chain = new CacheHandlerChain(engine);
    }

    private CacheContext createTestContext() {
        return CacheContext.of(CacheInput.builder()
                .operation(CacheOperation.GET)
                .cacheName("test-cache")
                .redisKey("test:key")
                .actualKey("test:key")
                .build());
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
        @DisplayName("addHandler 后 execute 委派最新不可变快照")
        void addHandler_refreshesFacadeSnapshot() {
            CacheHandler handler = new TestCacheHandler();
            CacheContext context = createTestContext();
            CacheResult expected = CacheResult.success();
            when(engine.execute(anyList(), same(context))).thenReturn(expected);

            chain.addHandler(handler);

            assertThat(chain.execute(context)).isSameAs(expected);
            verify(engine).execute(
                    argThat(snapshot -> snapshot.size() == 1 && snapshot.get(0) == handler),
                    same(context));
        }
    }
    @Nested
    @DisplayName("execute")
    class ExecuteTests {

        @Test
        @DisplayName("将不可变 handler 快照与 context 委派给 Engine")
        void execute_delegatesSnapshotAndContext() {
            CacheContext context = createTestContext();
            CacheResult expected = CacheResult.miss();
            when(engine.execute(anyList(), same(context))).thenReturn(expected);

            chain.addHandler(new TestCacheHandler());

            assertThat(chain.execute(context)).isSameAs(expected);
            verify(engine).execute(
                    argThat(snapshot -> snapshot.size() == 1),
                    same(context));
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
        @DisplayName("清空后将空快照委派给 Engine")
        void clear_emptyChain_delegatesEmptySnapshot() {
            CacheContext context = createTestContext();
            CacheResult expected = CacheResult.success();
            when(engine.execute(anyList(), same(context))).thenReturn(expected);

            chain.addHandler(new TestCacheHandler());
            chain.clear();

            assertThat(chain.execute(context)).isSameAs(expected);
            verify(engine).execute(
                    argThat(snapshot -> snapshot.isEmpty()),
                    same(context));
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

}
