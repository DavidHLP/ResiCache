package io.github.davidhlp.spring.cache.redis.cache;







import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.ChainContinuation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.FlowControl;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SyncLockHandler 单元测试
 */
@ExtendWith(MockitoExtension.class)
class SyncLockHandlerTest {

    @Mock
    private SyncSupport syncSupport;

    @Mock
    private RedisProCacheProperties properties;

    @Mock
    private RedisProCacheProperties.SyncLockProperties syncLockProperties;

    /** 句柄存根:多数用例只关心锁协议,不关心剩余链内容。 */
    private static final ChainContinuation NEXT = CacheResult::success;

    private SimpleMeterRegistry meterRegistry;

    private SyncLockHandler handler;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getSyncLock()).thenReturn(syncLockProperties);
        lenient().when(syncLockProperties.getTimeout()).thenReturn(3000L);
        lenient().when(syncLockProperties.getUnit()).thenReturn(java.util.concurrent.TimeUnit.MILLISECONDS);
        handler = new SyncLockHandler(syncSupport, new SyncLockTimeout(properties));
        meterRegistry = new SimpleMeterRegistry();
        handler.attachMeterRegistry(meterRegistry);
    }

    private CacheContext createContext(CacheOperation operation, RedisCacheableOperation cacheOperation) {
        CacheInput input = new CacheInput(
                operation,
                "test-cache",
                "test:key",
                "testKey",
                null,
                null,
                Duration.ofSeconds(60),
                cacheOperation
        );
        return new CacheContext(input);
    }

    private RedisCacheableOperation createSyncOperation(boolean sync, long syncTimeout) {
        return RedisCacheableOperation.builder()
                .name("test-cache")
                .cacheNames("test-cache")
                .sync(sync)
                .syncTimeout(syncTimeout)
                .build();
    }

    private double acquiredCount() {
        return meterRegistry.get("resicache.handler.sync.lock.acquired").counter().count();
    }


    @Nested
    @DisplayName("shouldHandle tests")
    class ShouldHandleTests {


        @Test
        @DisplayName("returns false when cache operation is null")
        void shouldHandle_cacheOperationNull_returnsFalse() {
            CacheContext context = createContext(CacheOperation.GET, null);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when sync is disabled")
        void shouldHandle_syncDisabled_returnsFalse() {
            RedisCacheableOperation operation = createSyncOperation(false, 10);
            CacheContext context = createContext(CacheOperation.GET, operation);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns true for GET with sync enabled")
        void shouldHandle_getWithSyncEnabled_returnsTrue() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.GET, operation);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns true for PUT_IF_ABSENT with sync enabled")
        void shouldHandle_putIfAbsentWithSyncEnabled_returnsTrue() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT, operation);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns true for PUT with sync enabled")
        void shouldHandle_putWithSyncEnabled_returnsTrue() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.PUT, operation);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false for REMOVE operation even with sync enabled")
        void shouldHandle_removeWithSyncEnabled_returnsFalse() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.REMOVE, operation);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false for CLEAN operation even with sync enabled")
        void shouldHandle_cleanWithSyncEnabled_returnsFalse() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.CLEAN, operation);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("doHandle tests")
    class DoHandleTests {
        @Test
        @DisplayName("成功持锁的 leader 执行 work lambda 时只计数一次")
        void doHandle_successfulLeader_incrementsAcquiredExactlyOnce() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.GET, operation);
            when(syncSupport.executeSync(anyString(), any(), anyLong())).thenAnswer(invocation -> {
                java.util.function.Supplier<CacheResult> work = invocation.getArgument(1);
                return work.get();
            });

            handler.doHandle(context, NEXT);

            assertThat(acquiredCount()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("锁超时未执行 work lambda → acquired counter 不变")
        void doHandle_lockTimeout_doesNotIncrementAcquired() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.GET, operation);
            when(syncSupport.executeSync(anyString(), any(), anyLong()))
                    .thenThrow(new IllegalStateException("Timed out acquiring lock"));

            assertThatThrownBy(() -> handler.doHandle(context, NEXT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Timed out acquiring lock");

            assertThat(acquiredCount()).isZero();
        }

        @Test
        @DisplayName("follower 拒绝等待且未执行 work lambda → acquired counter 不变")
        void doHandle_followerRefuses_doesNotIncrementAcquired() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.GET, operation);
            when(syncSupport.executeSync(anyString(), any(), anyLong()))
                    .thenThrow(new IllegalStateException("follower refuses to wait"));

            assertThatThrownBy(() -> handler.doHandle(context, NEXT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("follower refuses to wait");

            assertThat(acquiredCount()).isZero();
        }

        @Test
        @DisplayName("无锁后端 fail-fast 且未执行 work lambda → acquired counter 不变")
        void doHandle_noBackendFailFast_doesNotIncrementAcquired() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.GET, operation);
            when(syncSupport.executeSync(anyString(), any(), anyLong()))
                    .thenThrow(new IllegalStateException("No distributed lock backend"));

            assertThatThrownBy(() -> handler.doHandle(context, NEXT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No distributed lock backend");

            assertThat(acquiredCount()).isZero();
        }

        @Test
        @DisplayName("follower joining leader result never executes work lambda")
        void doHandle_followerJoinsResult_doesNotIncrementAcquired() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.GET, operation);
            when(syncSupport.executeSync(anyString(), any(), anyLong())).thenReturn(CacheResult.success());

            handler.doHandle(context, NEXT);

            assertThat(acquiredCount()).isZero();
        }

        @Test
        @DisplayName("executes in lock when lock required")
        void doHandle_lockRequired_executesWithLock() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CacheResult expectedResult = CacheResult.success();
            when(syncSupport.executeSync(anyString(), any(), anyLong())).thenReturn(expectedResult);

            HandlerResult result = handler.doHandle(context, NEXT);

            assertThat(result.decision()).isEqualTo(FlowControl.TERMINATE);
            assertThat(result.result()).isEqualTo(expectedResult);
            verify(syncSupport).executeSync(eq("test:key"), any(), eq(10L));
        }

        @Test
        @DisplayName("写路径走独占执行:并发写不得被 single-flight 合并(否则本笔写被丢弃)")
        void doHandle_lockRequiredForPut_usesExclusiveExecution() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.PUT, operation);
            CacheResult expectedResult = CacheResult.success();
            when(syncSupport.executeExclusive(anyString(), any(), anyLong())).thenReturn(expectedResult);

            HandlerResult result = handler.doHandle(context, NEXT);

            assertThat(result.decision()).isEqualTo(FlowControl.TERMINATE);
            verify(syncSupport).executeExclusive(eq("test:key"), any(), eq(10L));
            verify(syncSupport, never()).executeSync(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("uses default timeout when operation timeout is zero")
        void doHandle_zeroTimeout_usesDefaultTimeout() {
            RedisCacheableOperation operation = createSyncOperation(true, 0);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CacheResult expectedResult = CacheResult.success();
            when(syncSupport.executeSync(anyString(), any(), anyLong())).thenReturn(expectedResult);

            handler.doHandle(context, NEXT);

            verify(syncSupport).executeSync(eq("test:key"), any(), eq(10L));
        }

        @Test
        @DisplayName("uses global config timeout when operation timeout is negative")
        void doHandle_negativeTimeout_usesGlobalConfigTimeout() {
            RedisCacheableOperation operation = createSyncOperation(true, -5);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CacheResult expectedResult = CacheResult.success();
            when(syncSupport.executeSync(anyString(), any(), anyLong())).thenReturn(expectedResult);

            handler.doHandle(context, NEXT);

            verify(syncSupport).executeSync(eq("test:key"), any(), eq(3L));
        }

        @Test
        @DisplayName("sets lock acquired attribute to prevent duplicate locking")
        void doHandle_setsLockAcquiredAttribute() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.GET, operation);
            when(syncSupport.executeSync(anyString(), any(), anyLong())).thenReturn(CacheResult.success());

            handler.doHandle(context, NEXT);
        }

        @Test
        @DisplayName("executes chain inside lock")
        void doHandle_executesChainInsideLock() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.GET, operation);
            AtomicInteger advances = new AtomicInteger();
            when(syncSupport.executeSync(anyString(), any(), anyLong())).thenAnswer(invocation -> {
                java.util.function.Supplier<CacheResult> supplier = invocation.getArgument(1);
                return supplier.get();
            });

            handler.doHandle(context, () -> {
                advances.incrementAndGet();
                return CacheResult.success();
            });

            verify(syncSupport).executeSync(eq("test:key"), any(), eq(10L));
            assertThat(advances.get())
                    .as("剩余链必须在锁内推进恰好一次(句柄被传给锁 lambda)")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("单参 handle 由基类提供拒绝推进的 continuation")
        void handle_singleArgForm_usesBaseRejectionContinuation() {
            CacheContext context = createContext(CacheOperation.GET, createSyncOperation(true, 10));
            when(syncSupport.executeSync(anyString(), any(), anyLong())).thenAnswer(invocation -> {
                java.util.function.Supplier<CacheResult> work = invocation.getArgument(1);
                return work.get();
            });

            assertThatThrownBy(() -> handler.handle(context))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
