package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.core.writer.support.lock.SyncSupport;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
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

    private SyncLockHandler handler;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getSyncLock()).thenReturn(syncLockProperties);
        lenient().when(syncLockProperties.getTimeout()).thenReturn(3000L);
        lenient().when(syncLockProperties.getUnit()).thenReturn(java.util.concurrent.TimeUnit.MILLISECONDS);
        handler = new SyncLockHandler(syncSupport, properties);
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

    @Nested
    @DisplayName("shouldHandle tests")
    class ShouldHandleTests {

        @Test
        @DisplayName("returns false when lock already acquired by upstream")
        void shouldHandle_lockAlreadyAcquired_returnsFalse() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.GET, operation);
            context.setAttribute("sync.lock.acquired", true);

            boolean result = handler.shouldHandle(context);

            assertThat(result).isFalse();
        }

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
        @DisplayName("executes in lock when lock required")
        void doHandle_lockRequired_executesWithLock() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CacheResult expectedResult = CacheResult.success();
            when(syncSupport.executeSync(anyString(), any(), anyLong())).thenReturn(expectedResult);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(ChainDecision.TERMINATE);
            assertThat(result.result()).isEqualTo(expectedResult);
            verify(syncSupport).executeSync(eq("test:key"), any(), eq(10L));
        }

        @Test
        @DisplayName("terminates chain when lock required regardless of operation type")
        void doHandle_lockRequiredForPut_terminatesChain() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.PUT, operation);
            CacheResult expectedResult = CacheResult.success();
            when(syncSupport.executeSync(anyString(), any(), anyLong())).thenReturn(expectedResult);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.decision()).isEqualTo(ChainDecision.TERMINATE);
            verify(syncSupport).executeSync(eq("test:key"), any(), eq(10L));
        }

        @Test
        @DisplayName("uses default timeout when operation timeout is zero")
        void doHandle_zeroTimeout_usesDefaultTimeout() {
            RedisCacheableOperation operation = createSyncOperation(true, 0);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CacheResult expectedResult = CacheResult.success();
            when(syncSupport.executeSync(anyString(), any(), anyLong())).thenReturn(expectedResult);

            handler.doHandle(context);

            verify(syncSupport).executeSync(eq("test:key"), any(), eq(10L));
        }

        @Test
        @DisplayName("uses global config timeout when operation timeout is negative")
        void doHandle_negativeTimeout_usesGlobalConfigTimeout() {
            RedisCacheableOperation operation = createSyncOperation(true, -5);
            CacheContext context = createContext(CacheOperation.GET, operation);
            CacheResult expectedResult = CacheResult.success();
            when(syncSupport.executeSync(anyString(), any(), anyLong())).thenReturn(expectedResult);

            handler.doHandle(context);

            verify(syncSupport).executeSync(eq("test:key"), any(), eq(3L));
        }

        @Test
        @DisplayName("sets lock acquired attribute to prevent duplicate locking")
        void doHandle_setsLockAcquiredAttribute() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.GET, operation);
            when(syncSupport.executeSync(anyString(), any(), anyLong())).thenReturn(CacheResult.success());

            handler.doHandle(context);

            assertThat(context.getAttribute("sync.lock.acquired", false)).isTrue();
        }

        @Test
        @DisplayName("executes chain inside lock")
        void doHandle_executesChainInsideLock() {
            RedisCacheableOperation operation = createSyncOperation(true, 10);
            CacheContext context = createContext(CacheOperation.GET, operation);
            AtomicReference<CacheResult> capturedResult = new AtomicReference<>();
            when(syncSupport.executeSync(anyString(), any(), anyLong())).thenAnswer(invocation -> {
                java.util.function.Supplier<CacheResult> supplier = invocation.getArgument(1);
                return supplier.get();
            });

            handler.doHandle(context);

            verify(syncSupport).executeSync(eq("test:key"), any(), eq(10L));
        }
    }
}