package io.github.davidhlp.spring.cache.redis.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

class CachedValueExpiryTest {

    @Test
    void monotonicClock_ignoresWallClockAndChecksExpiryBoundary() {
        CachedValue value = CachedValue.of("value", 2);
        ReflectionTestUtils.setField(value, "startNanoTime", 1L);
        assertThat(value.isUsingMonotonicClock()).isTrue();
        assertThat(value.checkExpired(1 + TimeUnit.MILLISECONDS.toNanos(1999), Long.MAX_VALUE)).isFalse();
        assertThat(value.checkExpired(1 + TimeUnit.SECONDS.toNanos(2), 0)).isTrue();
        assertThat(value.getRemainingTtl(1, Long.MAX_VALUE)).isEqualTo(2);
        assertThat(value.getRemainingTtl(1 + TimeUnit.SECONDS.toNanos(1), 0)).isEqualTo(1);
        assertThat(value.getRemainingTtl(1 + TimeUnit.MILLISECONDS.toNanos(1001), 0)).isZero();
        assertThat(value.getRemainingTtl(1 + TimeUnit.SECONDS.toNanos(3), 0)).isZero();
    }

    @Test
    void deserializedValue_usesWallClockWithSameBoundaryAndRemainingSeconds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CachedValue original = CachedValue.forTest("value", 2, 1000, 7, false);
        CachedValue value = mapper.readValue(mapper.writeValueAsBytes(original), CachedValue.class);
        assertThat(value.getStartNanoTime()).isZero();
        assertThat(value.isUsingMonotonicClock()).isFalse();
        assertThat(value.checkExpired(Long.MAX_VALUE, 2999)).isFalse();
        assertThat(value.checkExpired(0, 3000)).isTrue();
        assertThat(value.getRemainingTtl(Long.MAX_VALUE, 1000)).isEqualTo(2);
        assertThat(value.getRemainingTtl(0, 2000)).isEqualTo(1);
        assertThat(value.getRemainingTtl(0, 2001)).isZero();
        assertThat(value.getRemainingTtl(0, 4000)).isZero();
    }

    @Test
    void explicitExpiryWinsAndNonPositiveTtlIsPermanent() {
        for (long ttl : new long[]{0, -1}) {
            CachedValue permanent = CachedValue.forTest("value", ttl, 1, 7, false);
            assertThat(permanent.checkExpired(Long.MAX_VALUE, Long.MAX_VALUE)).isFalse();
            assertThat(permanent.getRemainingTtl(Long.MAX_VALUE, Long.MAX_VALUE)).isEqualTo(-1);
            CachedValue marked = CachedValue.forTest("value", ttl, 1, 7, true);
            assertThat(marked.checkExpired(0, 0)).isTrue();
        }
        assertThat(CachedValue.forTest("value", 60, 1, 7, true).checkExpired(0, 0)).isTrue();
    }
}
