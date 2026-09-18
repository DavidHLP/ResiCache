package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisProCacheWriterAsyncTest {

    @Mock
    private CacheStatisticsCollector statistics;

    @Mock
    private CacheValueCodec valueCodec;

    @Mock
    private CacheHandlerChainFactory chainFactory;

    @Mock
    private CacheHandlerChain chain;

    @Mock
    private CacheOperationResolver operationResolver;

    private RedisProCacheWriter writer;

    @BeforeEach
    void setUp() {
        when(chainFactory.createChain()).thenReturn(chain);
        writer = new RedisProCacheWriter(
                statistics, valueCodec, chainFactory, operationResolver);
        doAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(2);
            return work.get();
        }).when(operationResolver).runWithSnapshot(any(), any(), any());
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void retrieve_capturesOnceAndPassesSnapshotAndMdcToResolver() throws Exception {
        MethodSnapshot snapshot = snapshot("retrieve");
        Map<String, String> mdc = Map.of("traceId", "retrieve-trace");
        byte[] expected = "cached".getBytes();
        when(operationResolver.capture()).thenReturn(snapshot);
        when(chain.execute(any())).thenReturn(CacheResult.success(expected));
        MDC.setContextMap(mdc);

        assertThat(writer.retrieve("cache", "key".getBytes(), Duration.ofSeconds(1)).join())
                .containsExactly(expected);

        verify(operationResolver, times(1)).capture();
        verify(operationResolver, times(1))
                .runWithSnapshot(same(snapshot), eq(mdc), any());
    }

    @Test
    void store_capturesOnceAndPutFailureCompletesFutureExceptionally() throws Exception {
        MethodSnapshot snapshot = snapshot("store");
        Map<String, String> mdc = Map.of("traceId", "store-trace");
        IllegalStateException cause = new IllegalStateException("redis down");
        when(operationResolver.capture()).thenReturn(snapshot);
        when(chain.execute(any())).thenReturn(
                CacheResult.failure(CacheOperation.PUT, CacheResult.FailureKind.REDIS, cause));
        MDC.setContextMap(mdc);

        CompletableFuture<Void> future = writer.store(
                "cache", "key".getBytes(), "value".getBytes(), Duration.ofSeconds(1));

        assertThatThrownBy(future::join)
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasCauseInstanceOf(CacheOperationException.class);
        assertThat(future).isCompletedExceptionally();
        verify(operationResolver, times(1)).capture();
        verify(operationResolver, times(1))
                .runWithSnapshot(same(snapshot), eq(mdc), any());
    }

    private static MethodSnapshot snapshot(String methodName) throws Exception {
        Method method = Fixture.class.getDeclaredMethod(methodName);
        return MethodSnapshot.of(method, Fixture.class);
    }

    static final class Fixture {
        void retrieve() { }
        void store() { }
    }
}
