package io.github.davidhlp.spring.cache.redis.chain.handler;

import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheErrorHandler 单元测试。
 *
 * <p>单 {@link CacheErrorHandler#handleError(CacheOperation, String, String, Exception)}
 * 入口,per-operation 策略集中到 {@code STRATEGIES} 不可变 Map。本测试：
 *
 * <ul>
 *   <li>{@code handleException} 直策略调用测试（{@link StrategyDispatchTests}）</li>
 *   <li>1 个 parametric 测试（{@link PerOperationStrategyTests}）— 单一事实源 + pin 全部 op→策略映射</li>
 *   <li>{@link StrategySelectionTests} 作为策略语义总览</li>
 * </ul>
 *
 * <p>deletion test:删掉 {@code STRATEGIES} Map 或 {@code handleError} 入口,本测试集无法 pin
 * per-operation 策略。
 */
@DisplayName("CacheErrorHandler Tests")
class CacheErrorHandlerTest {

    private CacheErrorHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CacheErrorHandler();
    }

    private Exception createException(String message) {
        return new RuntimeException(message);
    }

    @Nested
    @DisplayName("handleException with explicit strategy")
    class StrategyDispatchTests {

        @Test
        @DisplayName("FAIL_FAST returns failure result with original cause")
        void handleException_failFast_returnsFailure() {
            Exception e = createException("Connection refused");

            CacheResult result = handler.handleException(
                    "GET", "test-cache", "key", e,
                    CacheErrorHandler.ErrorStrategy.FAIL_FAST);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getCause()).isSameAs(e);
            assertThat(result.getFailureKind()).isEqualTo("REDIS");
        }

        @Test
        @DisplayName("FAIL_FAST preserves operation metadata")
        void handleException_failFast_preservesOperation() {
            Exception e = createException("Error");

            CacheResult result = handler.handleException(
                    "PUT", "cache", "key", e,
                    CacheErrorHandler.ErrorStrategy.FAIL_FAST);

            assertThat(result.getOperation()).isEqualTo("PUT");
            assertThat(result.getOutcome()).isEqualTo("FAILURE");
        }

        @Test
        @DisplayName("FAIL_FAST sets success false on failure")
        void handleException_failFast_setsSuccessFalse() {
            Exception e = createException("Error");

            CacheResult result = handler.handleException(
                    "PUT", "cache", "key", e,
                    CacheErrorHandler.ErrorStrategy.FAIL_FAST);

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("GRACEFUL_DEGRADATION returns a miss with failure status")
        void handleException_gracefulDegradation_returnsFailureMiss() {
            Exception e = createException("Timeout");

            CacheResult result = handler.handleException(
                    "GET", "test-cache", "key", e,
                    CacheErrorHandler.ErrorStrategy.GRACEFUL_DEGRADATION);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getResultBytes()).isNull();
            assertThat(result.getFailureKind()).isEqualTo("REDIS");
            assertThat(result.getCause()).isSameAs(e);
        }

        @Test
        @DisplayName("SILENT returns an observable best-effort failure")
        void handleException_silent_returnsFailure() {
            Exception e = createException("Silent error");

            CacheResult result = handler.handleException(
                    "REMOVE", "test-cache", "key", e,
                    CacheErrorHandler.ErrorStrategy.SILENT);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getCause()).isSameAs(e);
        }

        @Test
        @DisplayName("SILENT does not throw exception")
        void handleException_silent_doesNotThrow() {
            Exception e = createException("Silent");

            CacheResult result = handler.handleException(
                    "CLEAN", "cache", "pattern", e,
                    CacheErrorHandler.ErrorStrategy.SILENT);

            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("classifies timeout, cancellation, and serialization failures")
        void handleException_classifiesFailureKinds() {
            CacheResult timeout = handler.handleException(
                    "GET", "cache", "key", new java.util.concurrent.TimeoutException("timeout"),
                    CacheErrorHandler.ErrorStrategy.GRACEFUL_DEGRADATION);
            CacheResult cancellation = handler.handleException(
                    "GET", "cache", "key", new java.util.concurrent.CancellationException("cancelled"),
                    CacheErrorHandler.ErrorStrategy.GRACEFUL_DEGRADATION);
            CacheResult serialization = handler.handleException(
                    "GET", "cache", "key",
                    new io.github.davidhlp.spring.cache.redis.serialization.SerializationException("bad"),
                    CacheErrorHandler.ErrorStrategy.GRACEFUL_DEGRADATION);

            assertThat(timeout.getFailureKind()).isEqualTo("TIMEOUT");
            assertThat(cancellation.getFailureKind()).isEqualTo("CANCELLATION");
            assertThat(serialization.getFailureKind()).isEqualTo("SERIALIZATION");
        }

    }

    /**
     * 单一事实源 pin — 每个 operation 的策略 + 期望 success 状态。
     * 新增 operation 时，{@link CacheOperation} 加枚举值 + {@code STRATEGIES} 加一行 + 本测试
     * 加一行参数,3 处同步驱动。
     */
    static Stream<Arguments> perOperationStrategies() {
        return Stream.of(
                Arguments.of(CacheOperation.GET, false),
                Arguments.of(CacheOperation.PUT, false),
                Arguments.of(CacheOperation.PUT_IF_ABSENT, false),
                Arguments.of(CacheOperation.REMOVE, false),
                Arguments.of(CacheOperation.CLEAN, false));
    }


    @Nested
    @DisplayName("handleError per-operation strategy")
    class PerOperationStrategyTests {

        @ParameterizedTest(name = "{0} → success={1}")
        @MethodSource("io.github.davidhlp.spring.cache.redis.chain.handler.CacheErrorHandlerTest#perOperationStrategies")
        @DisplayName("dispatches STRATEGIES map to handleException correctly")
        void handleError_dispatchesPerOperationStrategy(CacheOperation operation, boolean expectedSuccess) {
            Exception e = createException("Redis error for " + operation);

            CacheResult result = handler.handleError(operation, "test-cache", "key", e);

            assertThat(result.isSuccess()).isEqualTo(expectedSuccess);
        }
        @Test
        @DisplayName("GET uses GRACEFUL_DEGRADATION with failure status")
        void handleError_get_returnsObservableMiss() {
            Exception e = createException("Redis error");

            CacheResult result = handler.handleError(CacheOperation.GET, "test-cache", "key", e);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getResultBytes()).isNull();
            assertThat(result.getFailureKind()).isEqualTo("REDIS");
        }

        @Test
        @DisplayName("PUT uses FAIL_FAST (returns failure, success=false)")
        void handleError_put_returnsFailure() {
            Exception e = createException("Redis error");

            CacheResult result = handler.handleError(CacheOperation.PUT, "test-cache", "key", e);

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("PUT_IF_ABSENT uses FAIL_FAST")
        void handleError_putIfAbsent_returnsFailure() {
            Exception e = createException("Redis error");

            CacheResult result = handler.handleError(CacheOperation.PUT_IF_ABSENT, "test-cache", "key", e);

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("REMOVE uses observable best-effort failure")
        void handleError_remove_returnsFailureWithoutThrowing() {
            Exception e = createException("Redis error");

            CacheResult result = handler.handleError(CacheOperation.REMOVE, "test-cache", "key", e);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureKind()).isEqualTo("REDIS");
            assertThat(result.getCause()).isSameAs(e);
        }

        @Test
        @DisplayName("CLEAN uses FAIL_FAST")
        void handleError_clean_returnsFailure() {
            Exception e = createException("Redis error");

            CacheResult result = handler.handleError(CacheOperation.CLEAN, "test-cache", "pattern:*", e);

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("CLEAN accepts pattern as key parameter")
        void handleError_clean_acceptsPattern() {
            Exception e = createException("Redis error");
            String pattern = "cache:keys:*";

            CacheResult result = handler.handleError(CacheOperation.CLEAN, "cache", pattern, e);

            assertThat(result.isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("error strategy semantics")
    class StrategySelectionTests {

        @Test
        @DisplayName("FAIL_FAST appropriate for write operations (PUT, PUT_IF_ABSENT, CLEAN)")
        void failFast_appropriateForWrites() {
            Exception e = createException("Error");

            CacheResult putResult = handler.handleError(CacheOperation.PUT, "cache", "key", e);
            CacheResult putIfAbsentResult = handler.handleError(CacheOperation.PUT_IF_ABSENT, "cache", "key", e);
            CacheResult cleanResult = handler.handleError(CacheOperation.CLEAN, "cache", "pattern", e);

            assertThat(putResult.isSuccess()).isFalse();
            assertThat(putIfAbsentResult.isSuccess()).isFalse();
            assertThat(cleanResult.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("SILENT remains an observable best-effort policy for REMOVE")
        void silent_appropriateForRemoves() {
            Exception e = createException("Error");

            CacheResult result = handler.handleError(CacheOperation.REMOVE, "cache", "key", e);

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("GRACEFUL_DEGRADATION preserves GET miss and failure status")
        void gracefulDegradation_appropriateForReads() {
            Exception e = createException("Error");

            CacheResult result = handler.handleError(CacheOperation.GET, "cache", "key", e);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getResultBytes()).isNull();
        }
    }
}
