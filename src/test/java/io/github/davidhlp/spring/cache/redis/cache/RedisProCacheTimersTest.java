package io.github.davidhlp.spring.cache.redis.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RedisProCacheTimers} helper class 单元测试 —— ADR-0031 收敛目标.
 *
 * <p>本测试承担原 {@link RedisProCache} 6 处私有 {@code try-finally + safeRecord} 样板的测试覆盖:
 * <ul>
 *   <li>{@link RedisProCacheTimers#registerTimer} / {@link RedisProCacheTimers#registerCounter}
 *       —— registry=null 时返回 null;否则注册带正确 tag/description</li>
 *   <li>{@link RedisProCacheTimers#safeIncrement} —— counter=null 时静默 no-op;否则自增</li>
 *   <li>{@link RedisProCacheTimers#timed} —— timer=null 时直接执行 body;否则记录时长,
 *       body 抛异常时 finally 仍执行</li>
 *   <li>{@link RedisProCacheTimers#timedGet} —— 同 {@code timed} 但保留返回值</li>
 * </ul>
 */
@DisplayName("RedisProCacheTimers Tests")
class RedisProCacheTimersTest {

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @Nested
    @DisplayName("registerTimer Tests")
    class RegisterTimerTests {

        @Test
        @DisplayName("null registry returns null")
        void nullRegistry_returnsNull() {
            assertThat(RedisProCacheTimers.registerTimer(
                    null, "test.timer", "desc", "cache1")).isNull();
        }

        @Test
        @DisplayName("non-null registry registers Timer with cache tag")
        void nonNullRegistry_registersTimerWithTag() {
            Timer timer = RedisProCacheTimers.registerTimer(
                    meterRegistry, "resicache.cache.get", "Time spent getting", "userCache");

            assertThat(timer).isNotNull();
            assertThat(timer.getId().getName()).isEqualTo("resicache.cache.get");
            assertThat(timer.getId().getTag("cache")).isEqualTo("userCache");
            assertThat(timer.getId().getDescription()).isEqualTo("Time spent getting");
        }
    }

    @Nested
    @DisplayName("registerCounter Tests")
    class RegisterCounterTests {

        @Test
        @DisplayName("null registry returns null")
        void nullRegistry_returnsNull() {
            assertThat(RedisProCacheTimers.registerCounter(
                    null, "test.counter", "desc", "cache1")).isNull();
        }

        @Test
        @DisplayName("non-null registry registers Counter with cache tag")
        void nonNullRegistry_registersCounterWithTag() {
            Counter counter = RedisProCacheTimers.registerCounter(
                    meterRegistry, "resicache.cache.hit", "Cache hit count", "userCache");

            assertThat(counter).isNotNull();
            assertThat(counter.getId().getName()).isEqualTo("resicache.cache.hit");
            assertThat(counter.getId().getTag("cache")).isEqualTo("userCache");
        }
    }

    @Nested
    @DisplayName("safeIncrement Tests")
    class SafeIncrementTests {

        @Test
        @DisplayName("null counter is silent no-op")
        void nullCounter_silentNoOp() {
            // 必须不抛 NPE —— 这是 ADR-0031 行为保真的核心承诺
            RedisProCacheTimers.safeIncrement(null);
        }

        @Test
        @DisplayName("non-null counter increments")
        void nonNullCounter_increments() {
            Counter counter = meterRegistry.counter("test.counter");
            RedisProCacheTimers.safeIncrement(counter);
            RedisProCacheTimers.safeIncrement(counter);

            assertThat(counter.count()).isEqualTo(2.0);
        }
    }

    @Nested
    @DisplayName("timed Tests")
    class TimedTests {

        @Test
        @DisplayName("null timer executes body without recording")
        void nullTimer_executesBodyWithoutRecording() {
            AtomicInteger invocations = new AtomicInteger();

            RedisProCacheTimers.timed(null, () -> invocations.incrementAndGet());

            assertThat(invocations.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("non-null timer records one sample")
        void nonNullTimer_recordsOneSample() {
            Timer timer = meterRegistry.timer("test.timer");
            AtomicInteger invocations = new AtomicInteger();

            RedisProCacheTimers.timed(timer, () -> invocations.incrementAndGet());

            assertThat(invocations.get()).isEqualTo(1);
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("body exception is propagated and timer still records (finally semantics)")
        void bodyException_propagatesAndStillRecords() {
            Timer timer = meterRegistry.timer("test.timer");

            assertThatThrownBy(() -> RedisProCacheTimers.timed(timer, () -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");

            // 与原 try-finally 字节级等价:异常仍沿 finally 推进 timer
            assertThat(timer.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("timedGet Tests")
    class TimedGetTests {

        @Test
        @DisplayName("null timer returns supplier result without recording")
        void nullTimer_returnsResultWithoutRecording() {
            String result = RedisProCacheTimers.timedGet(null, () -> "hello");

            assertThat(result).isEqualTo("hello");
        }

        @Test
        @DisplayName("non-null timer records and returns result")
        void nonNullTimer_recordsAndReturnsResult() {
            Timer timer = meterRegistry.timer("test.timer");

            String result = RedisProCacheTimers.timedGet(timer, () -> "computed-value");

            assertThat(result).isEqualTo("computed-value");
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("supplier exception propagates; timer still records")
        void supplierException_propagatesAndStillRecords() {
            Timer timer = meterRegistry.timer("test.timer");
            AtomicReference<String> sideEffect = new AtomicReference<>();

            assertThatThrownBy(() -> RedisProCacheTimers.timedGet(timer, () -> {
                sideEffect.set("body-was-called");
                throw new RuntimeException("from supplier");
            })).isInstanceOf(RuntimeException.class)
                    .hasMessage("from supplier");

            assertThat(sideEffect.get()).isEqualTo("body-was-called");
            assertThat(timer.count()).isEqualTo(1);
        }
    }
}
