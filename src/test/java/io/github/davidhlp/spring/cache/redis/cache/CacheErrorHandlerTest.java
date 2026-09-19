package io.github.davidhlp.spring.cache.redis.cache;




import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheErrorHandler 单元测试。
 *
 * <p>单 {@link CacheErrorHandler#handleError(CacheOperation, String, Exception)}
 * 入口,per-operation 策略集中到 {@code STRATEGIES} 不可变 Map。本测试：
 *
 * <ul>
 *   <li>真实 operation 的失败结果与分类断言（{@link StrategyDispatchTests}）</li>
 *   <li>1 个 parametric 测试（{@link PerOperationStrategyTests}）— 经 handleError + 指标 tags
 *       pin 全部 op→策略映射，期望策略以字面量写在参数表中</li>
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

    /**
     * 失败结果与分类断言 —— 全部经真实 operation 的 handleError 出口，不再使用显式策略入口。
     */
    @Nested
    @DisplayName("handleError failure semantics via real operations")
    class StrategyDispatchTests {

        @Test
        @DisplayName("FAIL_FAST operation returns failure result with original cause")
        void failFast_returnsFailurePreservingCause() {
            Exception e = createException("Connection refused");

            CacheResult result = handler.handleError(CacheOperation.PUT, "test-cache", e);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.cause()).isSameAs(e);
            assertThat(result.failureKind()).isEqualTo(CacheResult.FailureKind.REDIS);
        }

        @Test
        @DisplayName("FAIL_FAST operation preserves operation metadata")
        void failFast_preservesOperation() {
            Exception e = createException("Error");

            CacheResult result = handler.handleError(CacheOperation.PUT, "cache", e);

            assertThat(result.operation()).isEqualTo(CacheOperation.PUT);
            assertThat(result.outcome()).isEqualTo(CacheResult.Outcome.FAILURE);
        }

        @Test
        @DisplayName("FAIL_FAST operation sets success false")
        void failFast_setsSuccessFalse() {
            Exception e = createException("Error");

            CacheResult result = handler.handleError(CacheOperation.PUT, "cache", e);

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("GET degrades to a miss with failure status")
        void gracefulDegradation_returnsFailureMiss() {
            Exception e = createException("Timeout");

            CacheResult result = handler.handleError(CacheOperation.GET, "test-cache", e);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.resultBytes()).isNull();
            assertThat(result.failureKind()).isEqualTo(CacheResult.FailureKind.REDIS);
            assertThat(result.cause()).isSameAs(e);
        }

        @Test
        @DisplayName("REMOVE returns an observable best-effort failure")
        void silent_returnsFailure() {
            Exception e = createException("Silent error");

            CacheResult result = handler.handleError(CacheOperation.REMOVE, "test-cache", e);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.cause()).isSameAs(e);
        }

        @Test
        @DisplayName("REMOVE best-effort does not throw")
        void silent_doesNotThrow() {
            Exception e = createException("Silent");

            org.assertj.core.api.Assertions.assertThatCode(
                            () -> handler.handleError(CacheOperation.REMOVE, "cache", e))
                    .doesNotThrowAnyException();

            CacheResult result = handler.handleError(CacheOperation.REMOVE, "cache", e);
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("classifies timeout, cancellation, serialization and generic failures")
        void classifiesFailureKinds() {
            CacheResult timeout = handler.handleError(CacheOperation.GET, "cache",
                    new java.util.concurrent.TimeoutException("timeout"));
            CacheResult cancellation = handler.handleError(CacheOperation.GET, "cache",
                    new java.util.concurrent.CancellationException("cancelled"));
            CacheResult serialization = handler.handleError(CacheOperation.GET, "cache",
                    new io.github.davidhlp.spring.cache.redis.serialization.SerializationException("bad"));
            CacheResult generic = handler.handleError(CacheOperation.GET, "cache",
                    createException("boom"));

            assertThat(timeout.failureKind()).isEqualTo(CacheResult.FailureKind.TIMEOUT);
            assertThat(cancellation.failureKind()).isEqualTo(CacheResult.FailureKind.CANCELLATION);
            assertThat(serialization.failureKind()).isEqualTo(CacheResult.FailureKind.SERIALIZATION);
            assertThat(generic.failureKind()).isEqualTo(CacheResult.FailureKind.REDIS);
        }

    }

    /**
     * 单一事实源 pin — 每个 operation 的期望策略（以字面量写死，不调用生产 strategyFor）。
     * 新增 operation 时，{@link CacheOperation} 加枚举值 + {@code STRATEGIES} 加一行 + 本测试
     * 加一行参数,3 处同步驱动。
     */
    static Stream<Arguments> perOperationStrategies() {
        return Stream.of(
                Arguments.of(CacheOperation.GET, CacheErrorHandler.ErrorStrategy.GRACEFUL_DEGRADATION),
                Arguments.of(CacheOperation.PUT, CacheErrorHandler.ErrorStrategy.FAIL_FAST),
                Arguments.of(CacheOperation.PUT_IF_ABSENT, CacheErrorHandler.ErrorStrategy.FAIL_FAST),
                Arguments.of(CacheOperation.REMOVE, CacheErrorHandler.ErrorStrategy.SILENT),
                Arguments.of(CacheOperation.CLEAN, CacheErrorHandler.ErrorStrategy.FAIL_FAST));
    }


    @Nested
    @DisplayName("handleError per-operation strategy")
    class PerOperationStrategyTests {

        @Test
        @DisplayName("strategyFor is the single read point of the strategy table")
        void strategyFor_exposesTheTableWithoutSideEffects() {
            assertThat(CacheErrorHandler.strategyFor(CacheOperation.GET))
                    .isEqualTo(CacheErrorHandler.ErrorStrategy.GRACEFUL_DEGRADATION);
            assertThat(CacheErrorHandler.strategyFor(CacheOperation.REMOVE))
                    .as("REMOVE 的 SILENT 是 writer '只 WARN 不抛' 的唯一来源")
                    .isEqualTo(CacheErrorHandler.ErrorStrategy.SILENT);
            assertThat(CacheErrorHandler.strategyFor(CacheOperation.PUT))
                    .isEqualTo(CacheErrorHandler.ErrorStrategy.FAIL_FAST);
            assertThat(CacheErrorHandler.strategyFor(null))
                    .as("null operation 保守判 FAIL_FAST")
                    .isEqualTo(CacheErrorHandler.ErrorStrategy.FAIL_FAST);
        }

        @ParameterizedTest(name = "{0} → {1}")
        @MethodSource("io.github.davidhlp.spring.cache.redis.cache.CacheErrorHandlerTest#perOperationStrategies")
        @DisplayName("handleError selects the operation's strategy; proven via metric tags")
        void handleError_dispatchesPerOperationStrategy(
                CacheOperation operation, CacheErrorHandler.ErrorStrategy expectedStrategy) {
            io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            CacheErrorHandler reportingHandler = new CacheErrorHandler(new CacheFailureReporter(registry));
            Exception e = createException("Redis error for " + operation);

            CacheResult result = reportingHandler.handleError(operation, "test-cache", e);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.failureKind()).isEqualTo(CacheResult.FailureKind.REDIS);
            var counters = registry.find(CacheFailureReporter.METRIC_NAME).counters();
            assertThat(counters).hasSize(1);
            io.micrometer.core.instrument.Counter counter = counters.iterator().next();
            assertThat(counter.count()).isEqualTo(1.0);
            assertThat(counter.getId().getTag("operation")).isEqualTo(operation.name());
            assertThat(counter.getId().getTag("kind")).isEqualTo("REDIS");
            assertThat(counter.getId().getTag("strategy")).isEqualTo(expectedStrategy.name());
        }

        @Test
        @DisplayName("CLEAN partial failure keeps the explicit PARTIAL_CLEAN kind")
        void handleError_clean_preservesExplicitPartialCleanKind() {
            io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            CacheErrorHandler reportingHandler = new CacheErrorHandler(new CacheFailureReporter(registry));
            // 普通 RuntimeException 若被自动分类会得到 REDIS；typed-kind 入口必须保留 PARTIAL_CLEAN
            RuntimeException cause = new RuntimeException("deleted some keys then failed");

            CacheResult result = reportingHandler.handleError(
                    CacheOperation.CLEAN, "test-cache", CacheResult.FailureKind.PARTIAL_CLEAN, cause);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.failureKind()).isEqualTo(CacheResult.FailureKind.PARTIAL_CLEAN);
            assertThat(result.cause()).isSameAs(cause);

            CacheOperationException thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> CacheErrorHandler.finalizeFailure(CacheOperation.CLEAN, "test-cache", result),
                    CacheOperationException.class);
            assertThat(thrown.getOperation()).isEqualTo(CacheOperation.CLEAN);
            assertThat(thrown.getFailureKind()).isEqualTo(CacheResult.FailureKind.PARTIAL_CLEAN);
            assertThat(thrown.getCause()).isSameAs(cause);

            var counters = registry.find(CacheFailureReporter.METRIC_NAME).counters();
            assertThat(counters).hasSize(1);
            io.micrometer.core.instrument.Counter counter = counters.iterator().next();
            assertThat(counter.count())
                    .as("finalizeFailure 不得重复计数")
                    .isEqualTo(1.0);
            assertThat(counter.getId().getTag("operation")).isEqualTo("CLEAN");
            assertThat(counter.getId().getTag("kind")).isEqualTo("PARTIAL_CLEAN");
            assertThat(counter.getId().getTag("strategy")).isEqualTo("FAIL_FAST");
        }
    }

    @Nested
    @DisplayName("error strategy semantics")
    class StrategySelectionTests {

        @Test
        @DisplayName("FAIL_FAST appropriate for write operations (PUT, PUT_IF_ABSENT, CLEAN)")
        void failFast_appropriateForWrites() {
            Exception e = createException("Error");

            CacheResult putResult = handler.handleError(CacheOperation.PUT, "cache", e);
            CacheResult putIfAbsentResult = handler.handleError(CacheOperation.PUT_IF_ABSENT, "cache", e);
            CacheResult cleanResult = handler.handleError(CacheOperation.CLEAN, "cache", e);

            assertThat(putResult.isSuccess()).isFalse();
            assertThat(putIfAbsentResult.isSuccess()).isFalse();
            assertThat(cleanResult.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("SILENT remains an observable best-effort policy for REMOVE")
        void silent_appropriateForRemoves() {
            Exception e = createException("Error");

            CacheResult result = handler.handleError(CacheOperation.REMOVE, "cache", e);

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("GRACEFUL_DEGRADATION preserves GET miss and failure status")
        void gracefulDegradation_appropriateForReads() {
            Exception e = createException("Error");

            CacheResult result = handler.handleError(CacheOperation.GET, "cache", e);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.resultBytes()).isNull();
        }
    }
    @Test
    @DisplayName("finalizeFailure centralizes typed FAIL_FAST completion")
    void finalizeFailure_failFast_throwsTypedExceptionWithoutRawKey() {
        String rawKey = "secret-customer-key-42";
        IllegalStateException cause = new IllegalStateException("redis failed for " + rawKey);
        CacheResult result = CacheResult.failure(
                CacheOperation.PUT, CacheResult.FailureKind.REDIS, cause);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> CacheErrorHandler.finalizeFailure(CacheOperation.PUT, "cache", result))
                .isInstanceOf(CacheOperationException.class)
                .hasCauseReference(cause)
                .hasMessageNotContaining(rawKey);
    }

    @Test
    @DisplayName("finalizeFailure keeps best-effort operations non-throwing")
    void finalizeFailure_remove_doesNotThrow() {
        CacheResult result = CacheResult.failure(
                CacheOperation.REMOVE,
                CacheResult.FailureKind.REDIS,
                new IllegalStateException("redis down"));

        org.assertj.core.api.Assertions.assertThatCode(
                () -> CacheErrorHandler.finalizeFailure(CacheOperation.REMOVE, "cache", result))
                .doesNotThrowAnyException();
    }


    @Nested
    @DisplayName("count-once failure metric")
    class FailureMetricTests {

        @Test
        @DisplayName("cache-path WARN/ERROR 日志不含 raw key / exception message")
        void cachePathLogs_omitRawKeyAndCauseMessage() {
            String secretKey = "secret-cache-key-77";
            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                            CacheErrorHandler.class.getName());
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
                    new ch.qos.logback.core.read.ListAppender<>();
            captured.start();
            logger.addAppender(captured);
            try {
                // 敏感 sentinel 现在只存在于异常 message（key 参数已删除）
                Exception e = new IllegalStateException("redis failed for key " + secretKey);
                handler.handleError(CacheOperation.PUT, "cache", e);

                assertThat(captured.list).isNotEmpty();
                String logs = captured.list.stream()
                        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .reduce("", String::concat);
                assertThat(logs)
                        .as("WARN/ERROR 日志不得含异常 message（可能嵌入 key）")
                        .doesNotContain(secretKey)
                        .doesNotContain("redis failed for key");
            } finally {
                logger.detachAppender(captured);
            }
        }

        @Test
        @DisplayName("一次失败 → 恰好一次 resicache.cache.failure 计数(不重复)")
        void singleFailure_reportsExactlyOnce() {
            io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            CacheErrorHandler reportingHandler = new CacheErrorHandler(
                    new io.github.davidhlp.spring.cache.redis.cache.CacheFailureReporter(registry));

            Exception e = createException("boom");
            // 同一事件只经 handleError 一次 → 计数恰好 1
            reportingHandler.handleError(CacheOperation.PUT, "cache", e);

            var counters = registry.find(
                    "resicache.cache.failure").counters();
            assertThat(counters).hasSize(1);
            io.micrometer.core.instrument.Counter counter = counters.iterator().next();
            assertThat(counter.count()).isEqualTo(1.0);
            assertThat(counter.getId().getTag("operation")).isEqualTo("PUT");
            assertThat(counter.getId().getTag("kind")).isEqualTo("REDIS");
            assertThat(counter.getId().getTag("strategy")).isEqualTo("FAIL_FAST");
        }

        @Test
        @DisplayName("无 registry(reporter=null)→ handler 不抛、无指标")
        void noRegistry_reportsNothing() {
            CacheErrorHandler plainHandler = new CacheErrorHandler(null);
            Exception e = createException("boom");

            CacheResult result = plainHandler.handleError(CacheOperation.GET, "cache", e);

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("null operation / kind / cause 被拒绝，且不产生失败计数")
        void nullInputs_rejectWithoutCounting() {
            io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            CacheErrorHandler reportingHandler = new CacheErrorHandler(new CacheFailureReporter(registry));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> reportingHandler.handleError(null, "cache", createException("boom")))
                    .isInstanceOf(NullPointerException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> reportingHandler.handleError(CacheOperation.GET, "cache", (Exception) null))
                    .isInstanceOf(NullPointerException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> reportingHandler.handleError(
                                    CacheOperation.GET, "cache", (CacheResult.FailureKind) null,
                                    createException("boom")))
                    .isInstanceOf(NullPointerException.class);

            assertThat(registry.find(CacheFailureReporter.METRIC_NAME).counters())
                    .as("构造失败结果前抛错 → 不进入指标上报")
                    .isEmpty();
        }
    }
}
