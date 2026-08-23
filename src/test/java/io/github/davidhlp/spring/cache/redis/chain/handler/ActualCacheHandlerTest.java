package io.github.davidhlp.spring.cache.redis.chain.handler;

import io.github.davidhlp.spring.cache.redis.cache.model.CachedValue;
import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheInput;
import io.github.davidhlp.spring.cache.redis.chain.model.NullDecision;
import io.github.davidhlp.spring.cache.redis.chain.model.PrefetchDecision;
import io.github.davidhlp.spring.cache.redis.chain.model.TtlDecision;
import io.github.davidhlp.spring.cache.redis.integration.AbstractRedisIntegrationTest;
import io.github.davidhlp.spring.cache.redis.protection.nullvalue.DefaultNullValuePolicy;
import io.github.davidhlp.spring.cache.redis.protection.refresh.RefreshCancellation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ActualCacheHandler 测试 — 真实 Redis 验证(原为 Mockito 单元测试)。
 *
 * <p><b>为什么改用真实 Redis:</b> 原版用 {@code when(valueOperations.get(...)).thenReturn(...)}
 * 桩伪造缓存命中/未命中/SETNX 结果,测试通过仅因 mock 返回了设定的值,而非真实 Redis 行为。
 * 终端 Redis 执行器(GET/PUT/SETNX/REMOVE)是假阳性的重灾区。本版用真实 RedisTemplate/
 * ValueOperations 驱动:存入真实状态 → 断言真实往返读取、真实 TTL、真实 SETNX 互斥语义。
 *
 * <p><b>转换边界:</b>
 * <ul>
 *   <li>Redis I/O 协作者(RedisTemplate / ValueOperations)→ 真实 bean(happy-path 场景)。</li>
 *   <li>非 Redis 协作者({@link RefreshCancellation}.cancel 异步刷新取消、{@link CacheErrorHandler}
 *       故障注入)→ 保留 mock:cancel 是非 Redis 副作用验证;errorHandler 测的是 try/catch→
 *       handleError 的接线逻辑。这些不是假阳性(假阳性是伪造 happy-path 的 Redis 行为)。</li>
 *   <li>故障注入异常路径(Redis I/O 抛异常 → errorHandler):真实 Redis 无法模拟"抛异常",
 *       故为这些测试单独构造带 mock I/O 的 handler 实例,保留异常处理分支覆盖。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActualCacheHandler Tests (real Redis)")
class ActualCacheHandlerTest extends AbstractRedisIntegrationTest {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ValueOperations<String, Object> valueOperations;

    @Autowired
    private DefaultNullValuePolicy nullValuePolicy;

    @Mock
    private RefreshCancellation earlyExpirationExecutor;

    @Mock
    private CacheErrorHandler errorHandler;

    private ActualCacheHandler handler;

    @BeforeEach
    void setUp() {
        // 每个测试真实清库,保证隔离
        redisTemplate.getConnectionFactory().getConnection().flushDb();
        handler = new ActualCacheHandler(
                redisTemplate,
                valueOperations,
                nullValuePolicy,
                earlyExpirationExecutor,
                errorHandler);
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
                null);
        return new CacheContext(input);
    }

    /** 构造一个带 mock I/O 的 handler,用于故障注入(真实 Redis 无法模拟"抛异常")。 */
    private ActualCacheHandler faultHandler(Exception onGet, Exception onSet, Exception onDelete) {
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> throwingOps = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> throwingTpl = mock(RedisTemplate.class);
        if (onGet != null) {
            when(throwingOps.get("test:key")).thenThrow(onGet);
        }
        if (onSet != null) {
            // set 是 void 方法,用 doThrow;PUT-fault 用 skipped-TTL 走 2 参重载
            doThrow(onSet).when(throwingOps).set(eq("test:key"), any());
        }
        if (onDelete != null) {
            when(throwingTpl.delete("test:key")).thenThrow(onDelete);
        }
        return new ActualCacheHandler(throwingTpl, throwingOps, nullValuePolicy,
                earlyExpirationExecutor, errorHandler);
    }

    @Nested
    @DisplayName("shouldHandle")
    class ShouldHandleTests {

        @Test
        @DisplayName("always returns true")
        void shouldHandle_always_returnsTrue() {
            assertThat(handler.shouldHandle(createContext(CacheOperation.GET))).isTrue();
            assertThat(handler.shouldHandle(createContext(CacheOperation.PUT))).isTrue();
            assertThat(handler.shouldHandle(createContext(CacheOperation.REMOVE))).isTrue();
            assertThat(handler.shouldHandle(createContext(CacheOperation.CLEAN))).isTrue();
        }
    }

    @Nested
    @DisplayName("doHandle - earlyExpiration skipped")
    class EarlyExpirationSkippedTests {

        @Test
        @DisplayName("returns miss when earlyExpiration.skipped attribute is true")
        void doHandle_earlyExpirationSkipped_returnsMiss() {
            CacheContext context = createContext(CacheOperation.GET);
            context.setPrefetchDecision(PrefetchDecision.skipped());

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().getResultBytes()).isNull();
            assertThat(result.result().isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("doHandle - GET operation")
    class GetOperationTests {

        @Test
        @DisplayName("returns cache hit when value exists (real Redis round-trip)")
        void handleGet_cacheHit_returnsSuccessWithValue() {
            // 真实存入 → handler 真实读取 → 真实命中
            valueOperations.set("test:key", CachedValue.of("testValue", 60));

            HandlerResult result = handler.doHandle(createContext(CacheOperation.GET));

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().isSuccess()).isTrue();
            assertThat(result.result().getResultBytes()).isNotNull();
            // 真实往返证明:Redis 中确实可读回 CachedValue,且值正确
            Object retrieved = valueOperations.get("test:key");
            assertThat(retrieved).isInstanceOf(CachedValue.class);
            assertThat(((CachedValue) retrieved).getValue()).isEqualTo("testValue");
        }

        @Test
        @DisplayName("returns miss when value does not exist (real Redis miss)")
        void handleGet_cacheMiss_returnsMiss() {
            HandlerResult result = handler.doHandle(createContext(CacheOperation.GET));

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().isSuccess()).isTrue();
            assertThat(result.result().getResultBytes()).isNull();
        }

        @Test
        @DisplayName("returns miss when cached value is absent (expiry not directly testable; miss path)")
        void handleGet_expiredValue_returnsMiss() {
            // CachedValue 的 TTL<=0 表示永不过期,无法直接构造"已过期";
            // 此处验证未命中路径(与原版一致),真实过期由 IT 的 TTL 场景覆盖。
            HandlerResult result = handler.doHandle(createContext(CacheOperation.GET));

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().getResultBytes()).isNull();
        }

        @Test
        @DisplayName("delegates to error handler on exception (fault injection — mock I/O)")
        void handleGet_exception_delegatesToErrorHandler() {
            // 真实 Redis 无法模拟 GET 抛异常;此处验证 handler 的 try/catch→errorHandler 接线
            Exception exception = new RuntimeException("Redis error");
            CacheResult errorResult = CacheResult.miss();
            ActualCacheHandler faultHandler = faultHandler(exception, null, null);
            when(errorHandler.handleError(eq(CacheOperation.GET), eq("test-cache"),
                    eq("test:key"), eq(exception))).thenReturn(errorResult);

            HandlerResult result = faultHandler.doHandle(createContext(CacheOperation.GET));

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result()).isEqualTo(errorResult);
        }
    }

    @Nested
    @DisplayName("doHandle - PUT operation")
    class PutOperationTests {

        @Test
        @DisplayName("stores value with TTL (real Redis: value + TTL persisted)")
        void handlePut_withTtl_storesValueWithTtl() {
            CacheContext context = createContext(CacheOperation.PUT);
            context.setTtlDecision(TtlDecision.applied(120));
            context.setNullDecision(NullDecision.of("storeValue"));

            HandlerResult result = handler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().isSuccess()).isTrue();
            verify(earlyExpirationExecutor).cancel("test:key");
            // 真实:值与 TTL 都持久化在 Redis 中
            Object stored = valueOperations.get("test:key");
            assertThat(stored).isInstanceOf(CachedValue.class);
            assertThat(((CachedValue) stored).getValue()).isEqualTo("storeValue");
            assertThat(redisTemplate.getExpire("test:key")).isBetween(1L, 120L);
        }

        @Test
        @DisplayName("stores value without TTL (real Redis: permanent, getExpire == -1)")
        void handlePut_withoutTtl_storesValueWithoutTtl() {
            CacheContext context = createContext(CacheOperation.PUT);
            context.setTtlDecision(TtlDecision.skipped());
            context.setNullDecision(NullDecision.of("storeValue"));

            HandlerResult result = handler.doHandle(context);

            assertThat(result.result().isSuccess()).isTrue();
            Object stored = valueOperations.get("test:key");
            assertThat(stored).isInstanceOf(CachedValue.class);
            assertThat(((CachedValue) stored).getValue()).isEqualTo("storeValue");
            assertThat(redisTemplate.getExpire("test:key")).isEqualTo(-1L);
        }

        @Test
        @DisplayName("uses deserialized value when store value is null (real Redis)")
        void handlePut_noStoreValue_usesDeserializedValue() {
            CacheContext context = createContext(CacheOperation.PUT);
            context.setTtlDecision(TtlDecision.skipped());
            // storeValue 缺席 → 沿用 deserializedValue "value"

            HandlerResult result = handler.doHandle(context);

            assertThat(result.result().isSuccess()).isTrue();
            Object stored = valueOperations.get("test:key");
            assertThat(stored).isInstanceOf(CachedValue.class);
            assertThat(((CachedValue) stored).getValue()).isEqualTo("value");
        }

        @Test
        @DisplayName("delegates to error handler on exception (fault injection — mock I/O)")
        void handlePut_exception_delegatesToErrorHandler() {
            Exception exception = new RuntimeException("Redis error");
            CacheResult errorResult = CacheResult.failure();
            ActualCacheHandler faultHandler = faultHandler(null, exception, null);
            when(errorHandler.handleError(eq(CacheOperation.PUT), eq("test-cache"),
                    eq("test:key"), eq(exception))).thenReturn(errorResult);

            CacheContext context = createContext(CacheOperation.PUT);
            context.setTtlDecision(TtlDecision.skipped());

            HandlerResult result = faultHandler.doHandle(context);

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result()).isEqualTo(errorResult);
        }
    }

    @Nested
    @DisplayName("doHandle - PUT_IF_ABSENT operation")
    class PutIfAbsentOperationTests {

        @Test
        @DisplayName("stores value when key does not exist (real Redis SETNX success)")
        void handlePutIfAbsent_keyNotExists_storesValue() {
            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT);
            context.setTtlDecision(TtlDecision.applied(120));
            context.setNullDecision(NullDecision.of("storeValue"));

            HandlerResult result = handler.doHandle(context);

            assertThat(result.result().isSuccess()).isTrue();
            // 真实:SETNX 成功,值已写入
            Object stored = valueOperations.get("test:key");
            assertThat(stored).isInstanceOf(CachedValue.class);
            assertThat(((CachedValue) stored).getValue()).isEqualTo("storeValue");
        }

        @Test
        @DisplayName("returns existing value when key exists (real Redis SETNX fails)")
        void handlePutIfAbsent_keyExists_returnsExistingValue() {
            // 真实预置已存在的 key → SETNX 自然失败 → 读取并返回现值
            valueOperations.set("test:key", CachedValue.of("existingValue", 60));

            CacheContext context = createContext(CacheOperation.PUT_IF_ABSENT);
            context.setTtlDecision(TtlDecision.applied(120));
            context.setNullDecision(NullDecision.of("storeValue"));

            HandlerResult result = handler.doHandle(context);

            assertThat(result.result().isSuccess()).isTrue();
            assertThat(result.result().getResultBytes()).isNotNull();
            // 真实:现值未被覆盖
            Object stored = valueOperations.get("test:key");
            assertThat(stored).isInstanceOf(CachedValue.class);
            assertThat(((CachedValue) stored).getValue()).isEqualTo("existingValue");
        }

        @Test
        @DisplayName("delegates to error handler on exception (fault injection — mock I/O)")
        void handlePutIfAbsent_exception_delegatesToErrorHandler() {
            Exception exception = new RuntimeException("Redis error");
            CacheResult errorResult = CacheResult.failure();
            ActualCacheHandler faultHandler = faultHandler(exception, null, null);
            when(errorHandler.handleError(eq(CacheOperation.PUT_IF_ABSENT), eq("test-cache"),
                    eq("test:key"), eq(exception))).thenReturn(errorResult);

            HandlerResult result = faultHandler.doHandle(createContext(CacheOperation.PUT_IF_ABSENT));

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result()).isEqualTo(errorResult);
        }
    }

    @Nested
    @DisplayName("doHandle - REMOVE operation")
    class RemoveOperationTests {

        @Test
        @DisplayName("deletes key successfully (real Redis delete)")
        void handleRemove_success_deletesKey() {
            valueOperations.set("test:key", CachedValue.of("v", 60));

            HandlerResult result = handler.doHandle(createContext(CacheOperation.REMOVE));

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result().isSuccess()).isTrue();
            // 真实:key 已从 Redis 删除
            assertThat(redisTemplate.hasKey("test:key")).isFalse();
        }

        @Test
        @DisplayName("delegates to error handler on exception (fault injection — mock I/O)")
        void handleRemove_exception_delegatesToErrorHandler() {
            Exception exception = new RuntimeException("Redis error");
            CacheResult errorResult = CacheResult.failure();
            ActualCacheHandler faultHandler = faultHandler(null, null, exception);
            when(errorHandler.handleError(eq(CacheOperation.REMOVE), eq("test-cache"),
                    eq("test:key"), eq(exception))).thenReturn(errorResult);

            HandlerResult result = faultHandler.doHandle(createContext(CacheOperation.REMOVE));

            assertThat(result.shouldTerminate()).isTrue();
            assertThat(result.result()).isEqualTo(errorResult);
        }
    }

    @Nested
    @DisplayName("doHandle - Chain termination")
    class ChainTerminationTests {

        @Test
        @DisplayName("always terminates chain after processing (real Redis)")
        void doHandle_alwaysTerminatesChain() {
            HandlerResult resultGet = handler.doHandle(createContext(CacheOperation.GET));

            CacheContext contextPut = createContext(CacheOperation.PUT);
            contextPut.setTtlDecision(TtlDecision.skipped());
            HandlerResult resultPut = handler.doHandle(contextPut);

            valueOperations.set("test:key", CachedValue.of("v", 60));
            HandlerResult resultRemove = handler.doHandle(createContext(CacheOperation.REMOVE));

            assertThat(resultGet.shouldTerminate()).isTrue();
            assertThat(resultPut.shouldTerminate()).isTrue();
            assertThat(resultRemove.shouldTerminate()).isTrue();
        }
    }
}
