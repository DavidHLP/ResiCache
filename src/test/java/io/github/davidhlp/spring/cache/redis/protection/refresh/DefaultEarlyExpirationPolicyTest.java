package io.github.davidhlp.spring.cache.redis.protection.refresh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DefaultEarlyExpirationPolicy 单元测试
 *
 * <p>ADR-0025:用例自 {@code protection.avalanche.DefaultTtlPolicyTest.ShouldEarlyExpirationTests} 原样迁入,
 * 断言逻辑 byte-for-byte 等价;仅方法名 {@code shouldEarlyExpiration_*} → {@code shouldRefresh_*},
 * 调用 {@code shouldEarlyExpiration} → {@code shouldRefresh} 对齐新 seam 命名。
 */
@DisplayName("DefaultEarlyExpirationPolicy Tests")
class DefaultEarlyExpirationPolicyTest {

    private DefaultEarlyExpirationPolicy policy;

    @Nested
    @DisplayName("shouldRefresh() Tests")
    class ShouldRefreshTests {

        private static final ZoneId UTC = ZoneId.of("UTC");
        private static final Instant FIXED_INSTANT = Instant.parse("2024-01-01T12:00:00Z");
        private static final long FIXED_TIME_MS = FIXED_INSTANT.toEpochMilli();

        @BeforeEach
        void setUp() {
            Clock fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
            policy = new DefaultEarlyExpirationPolicy(fixedClock);
        }

        @Test
        @DisplayName("returns false when ttlSeconds is zero")
        void shouldRefresh_zeroTtl_returnsFalse() {
            boolean result = policy.shouldRefresh(FIXED_TIME_MS, 0, 0.2);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when ttlSeconds is negative")
        void shouldRefresh_negativeTtl_returnsFalse() {
            boolean result = policy.shouldRefresh(FIXED_TIME_MS, -100, 0.2);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when threshold is zero")
        void shouldRefresh_zeroThreshold_returnsFalse() {
            boolean result = policy.shouldRefresh(FIXED_TIME_MS, 100, 0.0);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when threshold is negative")
        void shouldRefresh_negativeThreshold_returnsFalse() {
            boolean result = policy.shouldRefresh(FIXED_TIME_MS, 100, -0.1);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when threshold is 1")
        void shouldRefresh_thresholdOne_returnsFalse() {
            boolean result = policy.shouldRefresh(FIXED_TIME_MS, 100, 1.0);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when threshold is greater than 1")
        void shouldRefresh_thresholdGreaterThanOne_returnsFalse() {
            boolean result = policy.shouldRefresh(FIXED_TIME_MS, 100, 1.5);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns true when at threshold boundary")
        void shouldRefresh_atThreshold_returnsTrue() {
            // TTL 100 seconds, threshold 0.2 (80% used)
            // Current time: 1704100800000 (12:00:00)
            // At 80 seconds elapsed, ratio = 80/100 = 0.8 = 1 - threshold
            // createdTime = currentTime - elapsed = 1704100800000 - 80000
            long ttlSeconds = 100;
            double threshold = 0.2;
            long createdTime = FIXED_TIME_MS - 80000; // 80 seconds ago

            boolean result = policy.shouldRefresh(createdTime, ttlSeconds, threshold);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns true when past threshold")
        void shouldRefresh_pastThreshold_returnsTrue() {
            // Elapsed 90 seconds, ratio = 0.9 >= 0.8
            long ttlSeconds = 100;
            double threshold = 0.2;
            long createdTime = FIXED_TIME_MS - 90000; // 90 seconds ago

            boolean result = policy.shouldRefresh(createdTime, ttlSeconds, threshold);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false when before threshold")
        void shouldRefresh_beforeThreshold_returnsFalse() {
            // Elapsed 70 seconds, ratio = 0.7 < 0.8
            long ttlSeconds = 100;
            double threshold = 0.2;
            long createdTime = FIXED_TIME_MS - 70000; // 70 seconds ago

            boolean result = policy.shouldRefresh(createdTime, ttlSeconds, threshold);

            assertThat(result).isFalse();
        }
    }
}
