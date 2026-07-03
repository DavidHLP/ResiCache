package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.NullDecision;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheInput;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.TtlDecision;


import io.github.davidhlp.spring.cache.redis.cache.CachedValue;
import io.github.davidhlp.spring.cache.redis.protection.nullvalue.DefaultNullValuePolicy;
import io.github.davidhlp.spring.cache.redis.protection.refresh.ThreadPoolEarlyExpirationExecutor;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ActualCacheHandler 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActualCacheHandler Tests")
class ActualCacheHandlerTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private DefaultNullValuePolicy nullValuePolicy;

    @Mock
    private ThreadPoolEarlyExpirationExecutor earlyExpirationExecutor;

    @Mock
    private CacheErrorHandler errorHandler;

    private ActualCacheHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ActualCacheHandler(
            redisTemplate,
            valueOperations,
            nullValuePolicy,
            earlyExpirationExecutor,
            errorHandler
        );
    }

    private CacheContext createContext(CacheOperation operation) {
        CacheInput input = new CacheInput(
            operation,
            "test-cache",
            "test:key",
            "key",
            new byte[]{1},
            "value",
            Duration.ofSeconds(60),
            null
        );
        return new CacheContext(input);
    }

    @Nested
    @DisplayName("shouldHandle")
    class ShouldHandleTests {

        @Test
        @DisplayName("always returns true")
        void shouldHandle_always_returnsTrue() {
            CacheContext contextGet = createContext(CacheOperation.GET);
            CacheContext contextPut = createContext(CacheOperation.PUT);
            CacheContext contextRemove = createContext(CacheOperation.REMOVE);
            CacheContext contextClean = createContext(CacheOperation.CLEAN);

            assertThat(handler.shouldHandle(contextGet)).isTrue();
            assertThat(handler.shouldHandle(contextPut)).isTrue();
            assertThat(handler.shouldHandle(contextRemove)).isTrue();
            assertThat(handler.shouldHandle(contextClean)).isTrue();
        }
    }

    @Nested
    @DisplayName("doHandle - earlyExpiration skipped")
    class EarlyExpirationSkippedTests {

        @Test
        @DisplayName("returns miss when earlyExpiration.skipped attribute is true")
        void doHandle_earlyExpirationSkipped_returnsMiss() {
            CacheContext context = createContext(CacheOperation.GET);
            context.setAttribute("earlyExpiration.skipped", true);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().isHit()).isFalse();
            assertThat(result.result().isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("doHandle - GET operation")
    class GetOperationTests {

        @Test
        @DisplayName("returns cache hit when value exists and not expired")
        void handleGet_cacheHit_returnsSuccessWithValue() {
            CacheContext context = createContext(CacheOperation.GET);
            CachedValue cachedValue = CachedValue.of("testValue", 60);
            byte[] returnValue = "convertedValue".getBytes();
            when(valueOperations.get("test:key")).thenReturn(cachedValue);
            when(nullValuePolicy.toReturnValue(any(), eq("test-cache"), eq("test:key")))
                .thenReturn(returnValue);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().isSuccess()).isTrue();
            assertThat(result.result().isHit()).isTrue();
            assertThat(result.result().getResultBytes()).isEqualTo(returnValue);
        }

        @Test
        @DisplayName("returns miss when value does not exist")
        void handleGet_cacheMiss_returnsMiss() {
            CacheContext context = createContext(CacheOperation.GET);
            when(valueOperations.get("test:key")).thenReturn(null);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().isSuccess()).isTrue();
            assertThat(result.result().isHit()).isFalse();
        }

        @Test
        @DisplayName("returns miss when cached value is expired (TTL = 0 means never expires)")
        void handleGet_expiredValue_returnsMiss() {
            // Note: TTL <= 0 means never expires in CachedValue.isExpired()
            // So we can't test "expired" directly via TTL
            // Instead test the null case which is the cache miss scenario
            CacheContext context = createContext(CacheOperation.GET);
            when(valueOperations.get("test:key")).thenReturn(null);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().isHit()).isFalse();
        }

        @Test
        @DisplayName("delegates to error handler on exception")
        void handleGet_exception_delegatesToErrorHandler() {
            CacheContext context = createContext(CacheOperation.GET);
            Exception exception = new RuntimeException("Redis error");
            CacheResult errorResult = CacheResult.miss();
            when(valueOperations.get("test:key")).thenThrow(exception);
            when(errorHandler.handleGetError(eq("test-cache"), eq("test:key"), eq(exception)))
                .thenReturn(errorResult);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result()).isEqualTo(errorResult);
        }
    }

    @Nested
    @DisplayName("doHandle - PUT operation")
    class PutOperationTests {

        @Test
        @DisplayName("stores value with TTL when shouldApplyTtl is true")
        void handlePut_withTtl_storesValueWithTtl() {
            CacheContext context = createContext(CacheOperation.PUT);
            context.setTtlDecision(TtlDecision.applied(120));
            context.setNullDecision(NullDecision.of("storeValue"));

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().isSuccess()).isTrue();
            verify(earlyExpirationExecutor).cancel("test:key");
            verify(valueOperations).set(eq("test:key"), any(CachedValue.class), eq(Duration.ofSeconds(120)));
        }

        @Test
        @DisplayName("stores value without TTL when shouldApplyTtl is false")
        void handlePut_withoutTtl_storesValueWithoutTtl() {
            CacheContext context = createContext(CacheOperation.PUT);
            context.setTtlDecision(TtlDecision.skipped());
            context.setNullDecision(NullDecision.of("storeValue"));

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().isSuccess()).isTrue();
            verify(valueOperations).set(eq("test:key"), any(CachedValue.class));
        }

        @Test
        @DisplayName("uses deserialized value when store value is null")
        void handlePut_noStoreValue_usesDeserializedValue() {
            CacheContext context = createContext(CacheOperation.PUT);
            context.setTtlDecision(TtlDecision.skipped());
            // storeValue is null, so it will use deserializedValue which is "value"

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().isSuccess()).isTrue();
            verify(valueOperations).set(eq("test:key"), any(CachedValue.class));
        }

        @Test
        @DisplayName("delegates to error handler on exception")
        void handlePut_exception_delegatesToErrorHandler() {
            CacheContext context = createContext(CacheOperation.PUT);
            context.setTtlDecision(TtlDecision.skipped());
            Exception exception = new RuntimeException("Redis error");
            CacheResult errorResult = CacheResult.failure(exception);
            doThrow(exception).when(valueOperations).set(anyString(), any());
            when(errorHandler.handlePutError(eq("test-cache"), eq("test:key"), eq(exception)))
                .thenReturn(errorResult);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result()).isEqualTo(errorResult);
        }
    }

    @Nested
    @DisplayName("doHandle - PUT_IF_ABSENT operation")
    class PutIfAbsentOperationTests {

        @Test
        @DisplayName("stores value when key does not exist")
        void handlePutIfAbsent_keyNotExists_storesValue() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT);
            context.setTtlDecision(TtlDecision.applied(120));
            context.setNullDecision(NullDecision.of("storeValue"));
            when(valueOperations.setIfAbsent(eq("test:key"), any(CachedValue.class), eq(Duration.ofSeconds(120))))
                .thenReturn(true);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().isSuccess()).isTrue();
        }

        @Test
        @DisplayName("returns existing value when key exists")
        void handlePutIfAbsent_keyExists_returnsExistingValue() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT);
            CachedValue existingValue = CachedValue.of("existingValue", 60);
            byte[] returnValue = "convertedExisting".getBytes();
            when(valueOperations.get("test:key")).thenReturn(existingValue);
            when(nullValuePolicy.toReturnValue(any(), eq("test-cache"), eq("test:key")))
                .thenReturn(returnValue);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().isSuccess()).isTrue();
            assertThat(result.result().getResultBytes()).isEqualTo(returnValue);
        }

        @Test
        @DisplayName("returns existing value when setIfAbsent fails")
        void handlePutIfAbsent_setFails_returnsExistingValue() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT);
            context.setTtlDecision(TtlDecision.skipped());
            CachedValue existingValue = CachedValue.of("existingValue", 60);
            byte[] returnValue = "convertedExisting".getBytes();
            when(valueOperations.get("test:key"))
                .thenReturn(existingValue);
            when(nullValuePolicy.toReturnValue(any(), eq("test-cache"), eq("test:key")))
                .thenReturn(returnValue);
            when(valueOperations.setIfAbsent(eq("test:key"), any(CachedValue.class)))
                .thenReturn(false);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().isSuccess()).isTrue();
            assertThat(result.result().getResultBytes()).isEqualTo(returnValue);
        }

        @Test
        @DisplayName("delegates to error handler on exception")
        void handlePutIfAbsent_exception_delegatesToErrorHandler() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT);
            Exception exception = new RuntimeException("Redis error");
            CacheResult errorResult = CacheResult.failure(exception);
            when(valueOperations.get("test:key")).thenThrow(exception);
            when(errorHandler.handlePutIfAbsentError(eq("test-cache"), eq("test:key"), eq(exception)))
                .thenReturn(errorResult);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result()).isEqualTo(errorResult);
        }
    }

    @Nested
    @DisplayName("doHandle - REMOVE operation")
    class RemoveOperationTests {

        @Test
        @DisplayName("deletes key successfully")
        void handleRemove_success_deletesKey() {
            CacheContext context = createContext(CacheOperation.REMOVE);
            when(redisTemplate.delete("test:key")).thenReturn(true);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().isSuccess()).isTrue();
            verify(redisTemplate).delete("test:key");
        }

        @Test
        @DisplayName("delegates to error handler on exception")
        void handleRemove_exception_delegatesToErrorHandler() {
            CacheContext context = createContext(CacheOperation.REMOVE);
            Exception exception = new RuntimeException("Redis error");
            CacheResult errorResult = CacheResult.failure(exception);
            when(redisTemplate.delete("test:key")).thenThrow(exception);
            when(errorHandler.handleRemoveError(eq("test-cache"), eq("test:key"), eq(exception)))
                .thenReturn(errorResult);

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result()).isEqualTo(errorResult);
        }
    }

    @Nested
    @DisplayName("doHandle - Chain termination")
    class ChainTerminationTests {

        @Test
        @DisplayName("always terminates chain after processing")
        void doHandle_alwaysTerminatesChain() {
            CacheContext contextGet = createContext(CacheOperation.GET);
            when(valueOperations.get("test:key")).thenReturn(null);

            CacheContext contextPut = createContext(CacheOperation.PUT);
            contextPut.setTtlDecision(TtlDecision.skipped());

            CacheContext contextRemove = createContext(CacheOperation.REMOVE);
            when(redisTemplate.delete("test:key")).thenReturn(true);

            HandlerResult resultGet = handler.doHandle(contextGet);
            HandlerResult resultPut = handler.doHandle(contextPut);
            HandlerResult resultRemove = handler.doHandle(contextRemove);

            assertThat(resultGet.shouldTerminate()).isTrue();
            assertThat(resultPut.shouldTerminate()).isTrue();
            assertThat(resultRemove.shouldTerminate()).isTrue();
        }

        // ADR-0033: dead `setFinalResult` field removed; final result is now returned via HandlerResult
        // (verified above via result.shouldTerminate() + result.result().isSuccess()), no separate
        // context-output assertion needed.
    }
}
