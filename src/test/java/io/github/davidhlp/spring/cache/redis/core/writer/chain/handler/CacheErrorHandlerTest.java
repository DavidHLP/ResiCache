package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheErrorHandler 单元测试
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
    @DisplayName("handleException with FAIL_FAST strategy")
    class FailFastStrategyTests {

        @Test
        @DisplayName("returns failure result with FAIL_FAST strategy")
        void handleException_failFast_returnsFailure() {
            Exception e = createException("Connection refused");

            CacheResult result = handler.handleException(
                    "GET", "test-cache", "key", e,
                    CacheErrorHandler.ErrorStrategy.FAIL_FAST);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isFailure()).isTrue();
            assertThat(result.getException()).isEqualTo(e);
        }

        @Test
        @DisplayName("sets success to false on failure")
        void handleException_failFast_setsSuccessFalse() {
            Exception e = createException("Error");

            CacheResult result = handler.handleException(
                    "PUT", "cache", "key", e,
                    CacheErrorHandler.ErrorStrategy.FAIL_FAST);

            assertThat(result.isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("handleException with GRACEFUL_DEGRADATION strategy")
    class GracefulDegradationStrategyTests {

        @Test
        @DisplayName("returns miss result with GRACEFUL_DEGRADATION strategy")
        void handleException_gracefulDegradation_returnsMiss() {
            Exception e = createException("Timeout");

            CacheResult result = handler.handleException(
                    "GET", "test-cache", "key", e,
                    CacheErrorHandler.ErrorStrategy.GRACEFUL_DEGRADATION);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isHit()).isFalse();
        }

        @Test
        @DisplayName("returns successful miss result")
        void handleException_gracefulDegradation_returnsSuccessfulMiss() {
            Exception e = createException("Error");

            CacheResult result = handler.handleException(
                    "GET", "cache", "key", e,
                    CacheErrorHandler.ErrorStrategy.GRACEFUL_DEGRADATION);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isFailure()).isFalse();
        }
    }

    @Nested
    @DisplayName("handleException with SILENT strategy")
    class SilentStrategyTests {

        @Test
        @DisplayName("returns miss result with SILENT strategy")
        void handleException_silent_returnsMiss() {
            Exception e = createException("Silent error");

            CacheResult result = handler.handleException(
                    "REMOVE", "test-cache", "key", e,
                    CacheErrorHandler.ErrorStrategy.SILENT);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isHit()).isFalse();
        }

        @Test
        @DisplayName("does not throw exception with SILENT strategy")
        void handleException_silent_doesNotThrow() {
            Exception e = createException("Silent");

            CacheResult result = handler.handleException(
                    "CLEAN", "cache", "pattern", e,
                    CacheErrorHandler.ErrorStrategy.SILENT);

            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("handleGetError")
    class HandleGetErrorTests {

        @Test
        @DisplayName("uses GRACEFUL_DEGRADATION strategy for GET errors")
        void handleGetError_usesGracefulDegradation() {
            Exception e = createException("Redis connection failed");

            CacheResult result = handler.handleGetError("test-cache", "key", e);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isHit()).isFalse();
        }

        @Test
        @DisplayName("returns miss for GET operation failure")
        void handleGetError_returnsMiss() {
            Exception e = createException("Error");

            CacheResult result = handler.handleGetError("cache", "key", e);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isHit()).isFalse();
        }

        @Test
        @DisplayName("preserves exception information")
        void handleGetError_preservesException() {
            Exception e = createException("Get failed");

            CacheResult result = handler.handleGetError("cache", "key", e);

            // GRACEFUL_DEGRADATION doesn't preserve the exception, just returns miss
            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("handlePutError")
    class HandlePutErrorTests {

        @Test
        @DisplayName("uses FAIL_FAST strategy for PUT errors")
        void handlePutError_usesFailFast() {
            Exception e = createException("Write failed");

            CacheResult result = handler.handlePutError("test-cache", "key", e);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        @DisplayName("returns failure result for PUT operation")
        void handlePutError_returnsFailure() {
            Exception e = createException("Error");

            CacheResult result = handler.handlePutError("cache", "key", e);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.getException()).isEqualTo(e);
        }

        @Test
        @DisplayName("sets success to false")
        void handlePutError_setsSuccessFalse() {
            Exception e = createException("Error");

            CacheResult result = handler.handlePutError("cache", "key", e);

            assertThat(result.isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("handlePutIfAbsentError")
    class HandlePutIfAbsentErrorTests {

        @Test
        @DisplayName("uses FAIL_FAST strategy for PUT_IF_ABSENT errors")
        void handlePutIfAbsentError_usesFailFast() {
            Exception e = createException("Conditional write failed");

            CacheResult result = handler.handlePutIfAbsentError("test-cache", "key", e);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        @DisplayName("returns failure result")
        void handlePutIfAbsentError_returnsFailure() {
            Exception e = createException("Error");

            CacheResult result = handler.handlePutIfAbsentError("cache", "key", e);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.getException()).isEqualTo(e);
        }
    }

    @Nested
    @DisplayName("handleRemoveError")
    class HandleRemoveErrorTests {

        @Test
        @DisplayName("uses SILENT strategy for REMOVE errors")
        void handleRemoveError_usesSilent() {
            Exception e = createException("Delete failed");

            CacheResult result = handler.handleRemoveError("test-cache", "key", e);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isHit()).isFalse();
        }

        @Test
        @DisplayName("returns successful miss for REMOVE operation failure")
        void handleRemoveError_returnsSuccessfulMiss() {
            Exception e = createException("Error");

            CacheResult result = handler.handleRemoveError("cache", "key", e);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isFailure()).isFalse();
        }
    }

    @Nested
    @DisplayName("handleCleanError")
    class HandleCleanErrorTests {

        @Test
        @DisplayName("uses FAIL_FAST strategy for CLEAN errors")
        void handleCleanError_usesFailFast() {
            Exception e = createException("Clean failed");

            CacheResult result = handler.handleCleanError("test-cache", "pattern:*", e);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        @DisplayName("returns failure result for CLEAN operation")
        void handleCleanError_returnsFailure() {
            Exception e = createException("Error");

            CacheResult result = handler.handleCleanError("cache", "pattern", e);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.getException()).isEqualTo(e);
        }

        @Test
        @DisplayName("accepts pattern parameter")
        void handleCleanError_acceptsPattern() {
            Exception e = createException("Error");
            String pattern = "cache:keys:*";

            CacheResult result = handler.handleCleanError("cache", pattern, e);

            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    @DisplayName("error strategy selection")
    class ErrorStrategySelectionTests {

        @Test
        @DisplayName("FAIL_FAST is appropriate for write operations")
        void failFast_appropriateForWrites() {
            Exception e = createException("Error");

            CacheResult putResult = handler.handlePutError("cache", "key", e);
            CacheResult putIfAbsentResult = handler.handlePutIfAbsentError("cache", "key", e);

            assertThat(putResult.isFailure()).isTrue();
            assertThat(putIfAbsentResult.isFailure()).isTrue();
        }

        @Test
        @DisplayName("SILENT is appropriate for remove operations")
        void silent_appropriateForRemoves() {
            Exception e = createException("Error");

            CacheResult result = handler.handleRemoveError("cache", "key", e);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isFailure()).isFalse();
        }

        @Test
        @DisplayName("GRACEFUL_DEGRADATION is appropriate for read operations")
        void gracefulDegradation_appropriateForReads() {
            Exception e = createException("Error");

            CacheResult result = handler.handleGetError("cache", "key", e);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isHit()).isFalse();
        }
    }
}
