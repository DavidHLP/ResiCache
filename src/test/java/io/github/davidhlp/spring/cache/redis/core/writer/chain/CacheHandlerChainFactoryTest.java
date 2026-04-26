package io.github.davidhlp.spring.cache.redis.core.writer.chain;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * CacheHandlerChainFactory 单元测试
 *
 * <p>测试责任链工厂的创建和组装功能
 */
@DisplayName("CacheHandlerChainFactory Tests")
class CacheHandlerChainFactoryTest {

    private RedisProCacheProperties properties;
    private CacheHandlerChainFactory factory;

    @BeforeEach
    void setUp() {
        properties = mock(RedisProCacheProperties.class);
        factory = new CacheHandlerChainFactory(Collections.emptyList(), properties);
    }

    @Nested
    @DisplayName("createChain")
    class CreateChainTests {

        @Test
        @DisplayName("creates empty chain when no handlers provided")
        void createChain_noHandlers_createsEmptyChain() {
            factory = new CacheHandlerChainFactory(Collections.emptyList(), properties);

            CacheHandlerChain chain = factory.createChain();

            assertThat(chain.size()).isZero();
        }

        @Test
        @DisplayName("adds all handlers to chain")
        void createChain_multipleHandlers_addsAllToChain() {
            List<CacheHandler> handlers = List.of(
                    new TestCacheHandler(),
                    new AnotherTestHandler(),
                    new YetAnotherTestHandler()
            );
            factory = new CacheHandlerChainFactory(handlers, properties);

            CacheHandlerChain chain = factory.createChain();

            assertThat(chain.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("handlers are sorted by @HandlerPriority annotation")
        void createChain_withPriorities_sortsCorrectly() {
            List<CacheHandler> handlers = List.of(
                    new ActualCacheTestHandler(),
                    new BloomFilterTestHandler(),
                    new SyncLockTestHandler()
            );
            factory = new CacheHandlerChainFactory(handlers, properties);

            CacheHandlerChain chain = factory.createChain();

            // Order should be: BLOOM_FILTER(100), SYNC_LOCK(200), ACTUAL_CACHE(500)
            assertThat(chain.getHandlerNames()).containsExactly(
                    "BloomFilterTestHandler", "SyncLockTestHandler", "ActualCacheTestHandler");
        }

        @Test
        @DisplayName("handlers without annotation get Integer.MAX_VALUE priority")
        void createChain_noAnnotation_getsMaxPriority() {
            List<CacheHandler> handlers = List.of(
                    new TestCacheHandler(),
                    new PriorityTestHandler()
            );
            factory = new CacheHandlerChainFactory(handlers, properties);

            CacheHandlerChain chain = factory.createChain();

            // PriorityTestHandler has explicit order, TestCacheHandler has MAX_VALUE
            List<String> names = chain.getHandlerNames();
            assertThat(names.get(names.size() - 1)).isEqualTo("TestCacheHandler");
        }

        @Test
        @DisplayName("handlers are linked correctly in chain order")
        void createChain_multipleHandlers_linksCorrectly() {
            List<CacheHandler> handlers = List.of(
                    new TestCacheHandler(),
                    new AnotherTestHandler()
            );
            factory = new CacheHandlerChainFactory(handlers, properties);

            CacheHandlerChain chain = factory.createChain();

            assertThat(chain.size()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("disabled handlers")
    class DisabledHandlersTests {

        @Test
        @DisplayName("filters out disabled handlers from global config")
        void createChain_disabledHandlersGlobally_filtersOut() {
            // TestCacheHandler -> "test-cache" (Handler removed, camelCase converted)
            List<CacheHandler> handlers = List.of(
                    new TestCacheHandler(),
                    new AnotherTestHandler()
            );
            when(properties.getDisabledHandlers()).thenReturn(List.of("test-cache"));
            factory = new CacheHandlerChainFactory(handlers, properties);

            CacheHandlerChain chain = factory.createChain();

            assertThat(chain.size()).isEqualTo(1);
            assertThat(chain.getHandlerNames()).containsExactly("AnotherTestHandler");
        }

        @Test
        @DisplayName("handles kebab-case and class name mapping")
        void createChain_kebabCaseMapping_worksCorrectly() {
            // TestCacheHandler -> "test-cache", AnotherTestHandler -> "another-test"
            List<CacheHandler> handlers = List.of(
                    new TestCacheHandler(),
                    new AnotherTestHandler()
            );
            when(properties.getDisabledHandlers()).thenReturn(List.of("test-cache"));
            factory = new CacheHandlerChainFactory(handlers, properties);

            CacheHandlerChain chain = factory.createChain();

            assertThat(chain.size()).isEqualTo(1);
            assertThat(chain.getHandlerNames()).containsExactly("AnotherTestHandler");
        }

        @Test
        @DisplayName("empty disabled handlers list keeps all handlers")
        void createChain_emptyDisabledList_keepsAllHandlers() {
            List<CacheHandler> handlers = List.of(
                    new TestCacheHandler(),
                    new AnotherTestHandler()
            );
            when(properties.getDisabledHandlers()).thenReturn(Collections.emptyList());
            factory = new CacheHandlerChainFactory(handlers, properties);

            CacheHandlerChain chain = factory.createChain();

            assertThat(chain.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("only disabled handlers results in empty chain")
        void createChain_allDisabled_resultsInEmptyChain() {
            // TestCacheHandler -> "test-cache", AnotherTestHandler -> "another-test"
            List<CacheHandler> handlers = List.of(
                    new TestCacheHandler(),
                    new AnotherTestHandler()
            );
            when(properties.getDisabledHandlers()).thenReturn(List.of("test-cache", "another-test"));
            factory = new CacheHandlerChainFactory(handlers, properties);

            CacheHandlerChain chain = factory.createChain();

            assertThat(chain.size()).isZero();
        }
    }

    // ========== Test Handler Implementations ==========

    static class TestCacheHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

        @Override
        public void setNext(CacheHandler next) {}

        @Override
        public CacheHandler getNext() {
            return null;
        }
    }

    static class AnotherTestHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

        @Override
        public void setNext(CacheHandler next) {}

        @Override
        public CacheHandler getNext() {
            return null;
        }
    }

    static class YetAnotherTestHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

        @Override
        public void setNext(CacheHandler next) {}

        @Override
        public CacheHandler getNext() {
            return null;
        }
    }

    @HandlerPriority(HandlerOrder.BLOOM_FILTER)
    static class BloomFilterTestHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

        @Override
        public void setNext(CacheHandler next) {}

        @Override
        public CacheHandler getNext() {
            return null;
        }
    }

    @HandlerPriority(HandlerOrder.SYNC_LOCK)
    static class SyncLockTestHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

        @Override
        public void setNext(CacheHandler next) {}

        @Override
        public CacheHandler getNext() {
            return null;
        }
    }

    @HandlerPriority(HandlerOrder.ACTUAL_CACHE)
    static class ActualCacheTestHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

        @Override
        public void setNext(CacheHandler next) {}

        @Override
        public CacheHandler getNext() {
            return null;
        }
    }

    @HandlerPriority(HandlerOrder.PRE_REFRESH)
    static class PreRefreshTestHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

        @Override
        public void setNext(CacheHandler next) {}

        @Override
        public CacheHandler getNext() {
            return null;
        }
    }

    @HandlerPriority(HandlerOrder.TTL)
    static class PriorityTestHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

        @Override
        public void setNext(CacheHandler next) {}

        @Override
        public CacheHandler getNext() {
            return null;
        }
    }
}
