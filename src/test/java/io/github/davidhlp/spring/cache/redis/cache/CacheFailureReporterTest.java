package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.cache.CacheErrorHandler.ErrorStrategy;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult.FailureKind;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheFailureReporter 单元测试(ADR-06)。
 *
 * <p>锁定契约:
 * <ul>
 *   <li>唯一指标 {@code resicache.cache.failure},tag 仅 operation/kind/strategy
 *       (有限枚举低基数,无 cacheName/key/message)</li>
 *   <li>同 (op,kind,strategy) 多次上报 → 同一 counter 累加(非每事件新 counter)</li>
 *   <li>registry 缺失 → no-op 不抛</li>
 * </ul>
 */
@DisplayName("CacheFailureReporter Tests")
class CacheFailureReporterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CacheFailureReporter reporter = new CacheFailureReporter(registry);

    @Test
    @DisplayName("每次上报恰好一次 metric 计数,同 tag 组合累加")
    void report_incrementsSingleCounterPerTagCombo() {
        reporter.report(CacheOperation.PUT, FailureKind.REDIS, ErrorStrategy.FAIL_FAST);
        reporter.report(CacheOperation.PUT, FailureKind.REDIS, ErrorStrategy.FAIL_FAST);
        reporter.report(CacheOperation.GET, FailureKind.REDIS, ErrorStrategy.GRACEFUL_DEGRADATION);

        var counters = registry.find(CacheFailureReporter.METRIC_NAME).counters();
        assertThat(counters).hasSize(2);  // 两个不同 tag 组合
        Counter putCounter = counters.stream()
                .filter(c -> "PUT".equals(c.getId().getTag("operation")))
                .findFirst().orElseThrow();
        assertThat(putCounter.count()).isEqualTo(2.0);  // PUT 同组合上报 2 次 → 累加到 2
    }

    @Test
    @DisplayName("tag allowlist: 仅 operation/kind/strategy,无 cacheName/key/message")
    void tags_areFiniteEnumOnly() {
        reporter.report(CacheOperation.CLEAN, FailureKind.PARTIAL_CLEAN, ErrorStrategy.FAIL_FAST);

        var meters = registry.find(CacheFailureReporter.METRIC_NAME).meters();
        assertThat(meters).hasSize(1);
        io.micrometer.core.instrument.Meter meter = meters.iterator().next();
        Set<String> tagKeys = meter.getId().getTags().stream()
                .map(io.micrometer.core.instrument.Tag::getKey)
                .collect(Collectors.toSet());
        assertThat(tagKeys)
                .as("tag 必须仅含 operation/kind/strategy(无 cacheName/key/message 高基数)")
                .containsExactlyInAnyOrder("operation", "kind", "strategy");

        assertThat(meter.getId().getTag("operation")).isEqualTo("CLEAN");
        assertThat(meter.getId().getTag("kind")).isEqualTo("PARTIAL_CLEAN");
        assertThat(meter.getId().getTag("strategy")).isEqualTo("FAIL_FAST");
    }

    @Test
    @DisplayName("null 参数 → UNKNOWN tag(不抛)")
    void report_nullArgs_usesUnknownTag() {
        reporter.report(null, null, null);

        var meters = registry.find(CacheFailureReporter.METRIC_NAME).meters();
        assertThat(meters).hasSize(1);
        io.micrometer.core.instrument.Meter meter = meters.iterator().next();
        assertThat(meter.getId().getTag("operation")).isEqualTo("UNKNOWN");
        assertThat(meter.getId().getTag("kind")).isEqualTo("UNKNOWN");
        assertThat(meter.getId().getTag("strategy")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("registry 缺失 → no-op 不抛")
    void nullRegistry_noOp() {
        CacheFailureReporter noRegistry = new CacheFailureReporter(null);
        noRegistry.report(CacheOperation.PUT, FailureKind.REDIS, ErrorStrategy.FAIL_FAST);
        // 不抛即通过
    }

    @Test
    @DisplayName("cardinality 有界:枚举组合穷举后 counter 数有限")
    void cardinality_isBoundedByEnumProduct() {
        for (CacheOperation op : CacheOperation.values()) {
            for (FailureKind kind : FailureKind.values()) {
                reporter.report(op, kind, ErrorStrategy.FAIL_FAST);
            }
        }
        long maxCombos = (long) CacheOperation.values().length
                * FailureKind.values().length * ErrorStrategy.values().length;
        int actual = registry.find(CacheFailureReporter.METRIC_NAME).meters().size();
        assertThat((long) actual)
                .as("counter 数不超过枚举组合数(有界低基数)")
                .isLessThanOrEqualTo(maxCombos);
    }
}
