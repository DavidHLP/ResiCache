package io.github.davidhlp.spring.cache.redis.protection.avalanche;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DefaultTtlPolicy 单元测试。
 *
 * <p>覆盖 TTL 应用判定 + 抖动计算(DefaultTtlPolicy 无状态,无需 {@code Clock} mock)。
 */
@DisplayName("DefaultTtlPolicy Tests")
class DefaultTtlPolicyTest {

    private DefaultTtlPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DefaultTtlPolicy();
    }

    @Nested
    @DisplayName("shouldApply() Tests")
    class ShouldApplyTests {

        @Test
        @DisplayName("returns true for positive duration")
        void shouldApply_positiveDuration_returnsTrue() {
            Duration ttl = Duration.ofSeconds(60);

            boolean result = policy.shouldApply(ttl);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false for null duration")
        void shouldApply_nullDuration_returnsFalse() {
            boolean result = policy.shouldApply(null);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false for zero duration")
        void shouldApply_zeroDuration_returnsFalse() {
            Duration ttl = Duration.ZERO;

            boolean result = policy.shouldApply(ttl);

            assertThat(result).isFalse();
        }

        @ParameterizedTest
        @ValueSource(longs = {-1, -100, -1000})
        @DisplayName("returns false for negative duration")
        void shouldApply_negativeDuration_returnsFalse(long seconds) {
            Duration ttl = Duration.ofSeconds(seconds);

            boolean result = policy.shouldApply(ttl);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("calculateFinalTtl() Tests")
    class CalculateFinalTtlTests {

        @Test
        @DisplayName("returns -1 when baseTtl is null")
        void calculateFinalTtl_nullBaseTtl_returnsNegativeOne() {
            long result = policy.calculateFinalTtl(null, false, 0.1f);

            assertThat(result).isEqualTo(-1);
        }

        @Test
        @DisplayName("returns -1 when baseTtl is zero")
        void calculateFinalTtl_zeroBaseTtl_returnsNegativeOne() {
            long result = policy.calculateFinalTtl(0L, false, 0.1f);

            assertThat(result).isEqualTo(-1);
        }

        @ParameterizedTest
        @ValueSource(longs = {-1, -100})
        @DisplayName("returns -1 when baseTtl is negative")
        void calculateFinalTtl_negativeBaseTtl_returnsNegativeOne(long baseTtl) {
            long result = policy.calculateFinalTtl(baseTtl, false, 0.1f);

            assertThat(result).isEqualTo(-1);
        }

        @Test
        @DisplayName("returns baseTtl when randomTtl is false")
        void calculateFinalTtl_noRandom_returnsBaseTtl() {
            long baseTtl = 120L;

            long result = policy.calculateFinalTtl(baseTtl, false, 0.1f);

            assertThat(result).isEqualTo(baseTtl);
        }

        @Test
        @DisplayName("returns baseTtl when variance is zero")
        void calculateFinalTtl_zeroVariance_returnsBaseTtl() {
            long baseTtl = 120L;

            long result = policy.calculateFinalTtl(baseTtl, true, 0.0f);

            assertThat(result).isEqualTo(baseTtl);
        }

        @Test
        @DisplayName("returns baseTtl when variance is negative")
        void calculateFinalTtl_negativeVariance_returnsBaseTtl() {
            long baseTtl = 120L;

            long result = policy.calculateFinalTtl(baseTtl, true, -0.1f);

            assertThat(result).isEqualTo(baseTtl);
        }

        @Test
        @DisplayName("clamps variance to valid range 0-1")
        void calculateFinalTtl_varianceClampedToValidRange() {
            long baseTtl = 100L;

            // Variance > 1 should be clamped to 1
            long resultHigh = policy.calculateFinalTtl(baseTtl, true, 2.0f);
            assertThat(resultHigh).isGreaterThanOrEqualTo(1);
            assertThat(resultHigh).isLessThanOrEqualTo(baseTtl * 2);

            // Variance < 0 should be clamped to 0
            long resultLow = policy.calculateFinalTtl(baseTtl, true, -0.5f);
            assertThat(resultLow).isEqualTo(baseTtl);
        }

        @Test
        @DisplayName("returns value within valid range with random")
        void calculateFinalTtl_withRandom_returnsWithinValidRange() {
            long baseTtl = 100L;
            float variance = 0.3f;

            // Run multiple times to test random behavior
            for (int i = 0; i < 100; i++) {
                long result = policy.calculateFinalTtl(baseTtl, true, variance);

                // Result should be at least 1
                assertThat(result).isGreaterThanOrEqualTo(1);
                // Result should be at most baseTtl * 2
                assertThat(result).isLessThanOrEqualTo(baseTtl * 2);
            }
        }

        @Test
        @DisplayName("returns at least 1 when random factor causes zero or negative")
        void calculateFinalTtl_randomFactorMinimumOne() {
            long baseTtl = 100L;

            // With high variance, ensure minimum of 1 is never violated
            for (int i = 0; i < 1000; i++) {
                long result = policy.calculateFinalTtl(baseTtl, true, 1.0f);
                // Math.max(1, ...) should ensure result >= 1 always
                assertThat(result).isGreaterThanOrEqualTo(1);
                // Upper bound should also be respected
                assertThat(result).isLessThanOrEqualTo(baseTtl * 2);
            }
        }
    }
}
