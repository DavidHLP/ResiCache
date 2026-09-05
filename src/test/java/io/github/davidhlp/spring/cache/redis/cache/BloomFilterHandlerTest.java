package io.github.davidhlp.spring.cache.redis.cache;




import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BloomFilterHandler 单元测试 — marker-free 语义(ADR-01)。
 *
 * <p>锁定契约:
 * <ul>
 *   <li>GET:布隆判定确定 miss → 短路 miss + 计一次语义 counter;判定允许 → 继续链</li>
 *   <li>PUT / PUT_IF_ABSENT:仅在成功且非 skip 时回填 {@link BloomSupport#add}</li>
 *   <li>CLEAN:不触碰 Bloom(不 add、不 clear、无 rebuilding marker)</li>
 *   <li>{@link BloomGate} 只承担读侧判定;add/clear 全部收口于 {@link BloomSupport}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BloomFilterHandler Tests")
class BloomFilterHandlerTest {

    private static final String CACHE_NAME = "test-cache";
    private static final String KEY = "test:key";
    private static final String ACTUAL_KEY = "key";

    @Mock
    private BloomSupport bloomSupport;

    @Mock
    private BloomGate bloomGate;

    @Mock
    private CacheStatisticsCollector statistics;

    @Mock
    private RedisCacheableOperation cacheOperation;

    private BloomFilterHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BloomFilterHandler(bloomGate, bloomSupport, statistics);
    }

    private CacheContext createContext(CacheOperation operation) {
        return createContext(operation, ACTUAL_KEY);
    }

    private CacheContext createContext(CacheOperation operation, String actualKey) {
        CacheInput input = new CacheInput(
                operation,
                CACHE_NAME,
                "test:" + actualKey,
                actualKey,
                new byte[]{1},
                "value",
                null,
                cacheOperation);
        return new CacheContext(input);
    }

    @Nested
    @DisplayName("shouldHandle")
    class ShouldHandleTests {

        @Test
        @DisplayName("returns true when cacheOperation.useBloomFilter is true")
        void shouldHandle_bloomFilterEnabled_returnsTrue() {
            when(cacheOperation.isUseBloomFilter()).thenReturn(true);
            CacheContext context = createContext(CacheOperation.GET);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false when cacheOperation.useBloomFilter is false")
        void shouldHandle_bloomFilterDisabled_returnsFalse() {
            when(cacheOperation.isUseBloomFilter()).thenReturn(false);
            CacheContext context = createContext(CacheOperation.GET);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when cacheOperation is null")
        void shouldHandle_nullCacheOperation_returnsFalse() {
            CacheInput input = new CacheInput(
                    CacheOperation.GET,
                    CACHE_NAME,
                    "test:" + ACTUAL_KEY,
                    ACTUAL_KEY,
                    new byte[]{1},
                    "value",
                    null,
                    null);
            CacheContext context = new CacheContext(input);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("doHandle - GET operation")
    class GetOperationTests {

        @Test
        @DisplayName("returns miss and terminates when bloom filter rejects key")
        void handleGet_bloomRejects_returnsMissAndTerminates() {
            when(bloomGate.definiteMiss(CACHE_NAME, ACTUAL_KEY)).thenReturn(true);
            CacheContext context = createContext(CacheOperation.GET);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            verify(statistics).incMisses(CACHE_NAME);
        }

        @Test
        @DisplayName("continues chain when bloom filter allows key")
        void handleGet_bloomAllows_returnsContinueChain() {
            when(bloomGate.definiteMiss(CACHE_NAME, ACTUAL_KEY)).thenReturn(false);
            CacheContext context = createContext(CacheOperation.GET);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isFalse();
            verify(statistics, never()).incMisses(anyString());
        }
    }

    @Nested
    @DisplayName("doHandle - write operations")
    class WriteOperationTests {

        @Test
        @DisplayName("PUT continues chain (post-process deferred to afterChainExecution)")
        void handlePut_continuesChain() {
            CacheContext context = createContext(CacheOperation.PUT);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isFalse();
        }

        @Test
        @DisplayName("PUT_IF_ABSENT continues chain (post-process deferred to afterChainExecution)")
        void handlePutIfAbsent_continuesChain() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isFalse();
        }

        @Test
        @DisplayName("CLEAN continues chain without touching bloom")
        void handleClean_continuesChain() {
            CacheContext context = createContext(CacheOperation.CLEAN);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isFalse();
            verify(bloomGate, never()).definiteMiss(anyString(), anyString());
            verify(bloomSupport, never()).clear(anyString());
        }
    }

    @Nested
    @DisplayName("requiresPostProcess")
    class RequiresPostProcessTests {

        @Test
        @DisplayName("returns true for PUT")
        void requiresPostProcess_put_returnsTrue() {
            CacheContext context = createContext(CacheOperation.PUT);

            assertThat(handler.requiresPostProcess(context)).isTrue();
        }

        @Test
        @DisplayName("returns true for PUT_IF_ABSENT")
        void requiresPostProcess_putIfAbsent_returnsTrue() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT);

            assertThat(handler.requiresPostProcess(context)).isTrue();
        }

        @Test
        @DisplayName("returns false for GET (no post-process needed)")
        void requiresPostProcess_getOperation_returnsFalse() {
            CacheContext context = createContext(CacheOperation.GET);

            boolean result = handler.requiresPostProcess(context);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false for CLEAN because eviction does not touch Bloom")
        void requiresPostProcess_cleanOperation_returnsFalse() {
            CacheContext context = createContext(CacheOperation.CLEAN);

            assertThat(handler.requiresPostProcess(context)).isFalse();
        }
    }

    @Nested
    @DisplayName("afterChainExecution")
    class AfterChainExecutionTests {

        @Test
        @DisplayName("adds key to bloom filter on PUT success")
        void afterChainExecution_putSuccess_addsToBloomFilter() {
            CacheContext context = createContext(CacheOperation.PUT);

            handler.afterChainExecution(context, CacheResult.success());

            verify(bloomSupport).add(CACHE_NAME, ACTUAL_KEY);
        }

        @Test
        @DisplayName("adds key to bloom filter on PUT_IF_ABSENT success")
        void afterChainExecution_putIfAbsentSuccess_addsToBloomFilter() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT);

            handler.afterChainExecution(context, CacheResult.success());

            verify(bloomSupport).add(CACHE_NAME, ACTUAL_KEY);
        }

        @Test
        @DisplayName("does not touch Bloom on CLEAN success (marker-free: no clear, no marker I/O)")
        void afterChainExecution_cleanSuccess_doesNotTouchBloom() {
            CacheContext context = createContext(CacheOperation.CLEAN);

            handler.afterChainExecution(context, CacheResult.success());

            verify(bloomSupport, never()).add(anyString(), anyString());
            verify(bloomSupport, never()).clear(anyString());
        }

        @Test
        @DisplayName("does nothing when result is not success")
        void afterChainExecution_notSuccess_doesNothing() {
            CacheContext context = createContext(CacheOperation.PUT);

            handler.afterChainExecution(context,
                    CacheResult.failure(CacheOperation.PUT, CacheResult.FailureKind.REDIS,
                            new IllegalStateException("down")));

            verify(bloomSupport, never()).add(anyString(), anyString());
            verify(bloomSupport, never()).clear(anyString());
        }

        @Test
        @DisplayName("does nothing when context is skip remaining")
        void afterChainExecution_skipRemaining_doesNothing() {
            CacheContext context = createContext(CacheOperation.PUT);
            context.markSkipRemaining();

            handler.afterChainExecution(context, CacheResult.success());

            verify(bloomSupport, never()).add(anyString(), anyString());
        }

        @Test
        @DisplayName("skips processing when context is null")
        void afterChainExecution_nullContext_doesNothing() {
            handler.afterChainExecution(null, CacheResult.success());
            // Should not throw
        }

        @Test
        @DisplayName("skips processing when result is null")
        void afterChainExecution_nullResult_doesNothing() {
            handler.afterChainExecution(createContext(CacheOperation.PUT), null);
            // Should not throw
        }
    }

    @Nested
    @DisplayName("CLEAN→GET scenarios (ADR-01)")
    class CleanGetScenarios {

        /**
         * Scenario 1:CLEAN 后 GET 必须越过 Bloom 走缓存+loader,不得被静默短路。
         *
         * <p>旧行为:chain CLEAN 清空 Bloom → 空布隆 mightContain=false → GET 短路,
         * loader 永不执行(违反 @Cacheable)。marker-free:CLEAN 不触碰布隆,旧正位
         * 保留 → 仅产生安全的 false-positive(一次额外缓存查询),loader 永远可达。
         */
        @Test
        @DisplayName("GET after CLEAN can still invoke the loader")
        void getAfterClean_canReachLoader() {
            // 前置:key 曾在缓存中(布隆有位)
            handler.afterChainExecution(createContext(CacheOperation.PUT), CacheResult.success());
            verify(bloomSupport).add(CACHE_NAME, ACTUAL_KEY);

            // 普通缓存 CLEAN:CLEAN 后置不再触碰布隆(marker-free 核心)
            handler.afterChainExecution(createContext(CacheOperation.CLEAN), CacheResult.success());
            verify(bloomSupport, never()).clear(anyString());

            // 同一 key 的后续 GET:布隆判定基于"可能存在",不短路,loader 可达
            when(bloomGate.definiteMiss(CACHE_NAME, ACTUAL_KEY)).thenReturn(false);
            HandlerResult getResult = handler.doHandle(createContext(CacheOperation.GET));

            assertThat(getResult.shouldTerminate()).isFalse();
            verify(statistics, never()).incMisses(anyString());
        }

        /**
         * Scenario 2:重复 CLEAN 无状态残留 —— 每次 CLEAN 后置都是零副作用 no-op。
         */
        @Test
        @DisplayName("repeated CLEANs never touch bloom or stats")
        void repeatedCleans_staySideEffectFree() {
            // 连续多次 CLEAN:CLEAN 后置不得调用 clear/add(无 marker/window 依赖)
            for (int i = 0; i < 3; i++) {
                CacheContext cleanContext = createContext(CacheOperation.CLEAN);
                assertThat(handler.requiresPostProcess(cleanContext)).isFalse();
                handler.afterChainExecution(cleanContext, CacheResult.success());
            }
            verify(bloomSupport, never()).clear(anyString());
            verify(bloomSupport, never()).add(anyString(), anyString());

            // CLEAN 之后 PUT 回填不受任何状态残留影响(验证 mock 仍活、无副作用污染)
            handler.afterChainExecution(createContext(CacheOperation.PUT, "key:1"), CacheResult.success());
            verify(bloomSupport).add(CACHE_NAME, "key:1");
        }

        /**
         * Scenario 3:并发 GET 与 CLEAN —— 判定层故障时走 fail-open,GET 决策不短路。
         *
         * <p>BloomSupport 的 fail-open(true)契约由 BloomSupportTest 锁定;此处锁定
         * handler 侧:只要 {@link BloomGate#definiteMiss} 返回 false(可能 miss 判定
         * 无法作出),GET 必须继续,不许返回静默 miss。
         */
        @Test
        @DisplayName("concurrent GET/CLEAN keeps the GET decision fail-open")
        void concurrentCleanGet_getDecisionStaysFailOpen() {
            CacheContext getContext = createContext(CacheOperation.GET);
            when(bloomGate.definiteMiss(CACHE_NAME, ACTUAL_KEY)).thenReturn(false);

            HandlerResult result = handler.doHandle(getContext);

            assertThat(result.shouldTerminate()).isFalse();
        }

        /**
         * Scenario 4:CLEAN 只清缓存数据 —— 不打断后续 PUT 回填、不产生状态残留。
         */
        @Test
        @DisplayName("multiple keys keep their bloom bits across CLEANs")
        void multipleKeys_surviveAcrossCleans() {
            handler.afterChainExecution(createContext(CacheOperation.PUT, "key:1"), CacheResult.success());
            verify(bloomSupport).add(CACHE_NAME, "key:1");

            handler.afterChainExecution(createContext(CacheOperation.CLEAN), CacheResult.success());

            // 同一 cache 另一个 key 的 PUT_IF_ABSENT 仍正常回填(不因 CLEAN 有状态残留)
            handler.afterChainExecution(createContext(CacheOperation.PUT_IF_ABSENT, "key:2"), CacheResult.success());
            verify(bloomSupport).add(CACHE_NAME, "key:2");
            verify(bloomSupport, never()).clear(anyString());
        }
    }
}
