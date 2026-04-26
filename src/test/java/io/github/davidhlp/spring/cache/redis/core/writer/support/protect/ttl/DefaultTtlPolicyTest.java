package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.ttl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DefaultTtlPolicy 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultTtlPolicy Tests")
class DefaultTtlPolicyTest {

    @Mock
    private Clock clock;

    private DefaultTtlPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DefaultTtlPolicy(clock);
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

    @Nested
    @DisplayName("shouldPreRefresh() Tests")
    class ShouldPreRefreshTests {

        private static final ZoneId UTC = ZoneId.of("UTC");
        private static final Instant FIXED_INSTANT = Instant.parse("2024-01-01T12:00:00Z");
        private static final long FIXED_TIME_MS = FIXED_INSTANT.toEpochMilli();

        @BeforeEach
        void setUp() {
            Clock fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
            policy = new DefaultTtlPolicy(fixedClock);
        }

        @Test
        @DisplayName("returns false when ttlSeconds is zero")
        void shouldPreRefresh_zeroTtl_returnsFalse() {
            boolean result = policy.shouldPreRefresh(FIXED_TIME_MS, 0, 0.2);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when ttlSeconds is negative")
        void shouldPreRefresh_negativeTtl_returnsFalse() {
            boolean result = policy.shouldPreRefresh(FIXED_TIME_MS, -100, 0.2);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when threshold is zero")
        void shouldPreRefresh_zeroThreshold_returnsFalse() {
            boolean result = policy.shouldPreRefresh(FIXED_TIME_MS, 100, 0.0);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when threshold is negative")
        void shouldPreRefresh_negativeThreshold_returnsFalse() {
            boolean result = policy.shouldPreRefresh(FIXED_TIME_MS, 100, -0.1);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when threshold is 1")
        void shouldPreRefresh_thresholdOne_returnsFalse() {
            boolean result = policy.shouldPreRefresh(FIXED_TIME_MS, 100, 1.0);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when threshold is greater than 1")
        void shouldPreRefresh_thresholdGreaterThanOne_returnsFalse() {
            boolean result = policy.shouldPreRefresh(FIXED_TIME_MS, 100, 1.5);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns true when at threshold boundary")
        void shouldPreRefresh_atThreshold_returnsTrue() {
            // TTL 100 seconds, threshold 0.2 (80% used)
            // Current time: 1704100800000 (12:00:00)
            // At 80 seconds elapsed, ratio = 80/100 = 0.8 = 1 - threshold
            // createdTime = currentTime - elapsed = 1704100800000 - 80000
            long ttlSeconds = 100;
            double threshold = 0.2;
            long createdTime = FIXED_TIME_MS - 80000; // 80 seconds ago

            boolean result = policy.shouldPreRefresh(createdTime, ttlSeconds, threshold);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns true when past threshold")
        void shouldPreRefresh_pastThreshold_returnsTrue() {
            // Elapsed 90 seconds, ratio = 0.9 >= 0.8
            long ttlSeconds = 100;
            double threshold = 0.2;
            long createdTime = FIXED_TIME_MS - 90000; // 90 seconds ago

            boolean result = policy.shouldPreRefresh(createdTime, ttlSeconds, threshold);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false when before threshold")
        void shouldPreRefresh_beforeThreshold_returnsFalse() {
            // Elapsed 70 seconds, ratio = 0.7 < 0.8
            long ttlSeconds = 100;
            double threshold = 0.2;
            long createdTime = FIXED_TIME_MS - 70000; // 70 seconds ago

            boolean result = policy.shouldPreRefresh(createdTime, ttlSeconds, threshold);

            assertThat(result).isFalse();
        }
    }
}
