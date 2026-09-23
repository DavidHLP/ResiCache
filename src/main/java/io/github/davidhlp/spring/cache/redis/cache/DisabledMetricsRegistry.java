package io.github.davidhlp.spring.cache.redis.cache;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.noop.NoopCounter;
import io.micrometer.core.instrument.noop.NoopDistributionSummary;
import io.micrometer.core.instrument.noop.NoopFunctionCounter;
import io.micrometer.core.instrument.noop.NoopFunctionTimer;
import io.micrometer.core.instrument.noop.NoopGauge;
import io.micrometer.core.instrument.noop.NoopMeter;
import io.micrometer.core.instrument.noop.NoopTimer;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * metrics 关闭时的 sink —— 无状态、不持有任何 meter,故可全 JVM 共享一个实例。
 *
 * <p><b>为什么不能直接复用 {@code CompositeMeterRegistry}</b>:{@code MeterRegistry} 的注册
 * 路径({@code getOrCreateMeter})总会把 meter 放进自己的 {@code meterMap} /
 * {@code preFilterIdToMeterMap};没有任何 child 的 composite 只是"不发布",它仍然
 * <b>记住</b>每一个被问过的 meter id 与 tag 字符串。对动态命名的 cache 而言这张表会随
 * context 生命周期无限增长 —— 这正是 metrics 关闭时不该发生的事。
 *
 * <p>本类因此做两件事:
 * <ol>
 *   <li>构造期装一个 deny-all {@link MeterFilter} —— 基类的 {@code accept()} 在
 *       {@code meterMap.put} 之前短路,注册返回基类自带的 {@code Noop*} 实例,
 *       <b>不落任何 map</b>(这也让八个 abstract 方法实际不可达,仅作为基类契约的
 *       完整实现而存在);</li>
 *   <li>八个 abstract 成员全部返回 Micrometer 公开的 {@code io.micrometer.core.instrument.noop.*}
 *       类型,万一基类的短路路径将来变化,也仍然不会分配或保留真实 meter。</li>
 * </ol>
 *
 * <p>实例本身无状态(无 child registry、无 meter 表),所以 {@link #INSTANCE} 是被
 * {@link ResolvedMetrics} 共享的单例,而不是每次决议新建一个。
 */
final class DisabledMetricsRegistry extends MeterRegistry {

    /** 全 JVM 共享的无状态 sink;{@link ResolvedMetrics} 关闭路径唯一取值。 */
    static final MeterRegistry INSTANCE = new DisabledMetricsRegistry();

    /** 该 registry 是否为关闭路径的 no-op seam —— 关闭路径唯一判据。 */
    static boolean isDisabledSeam(MeterRegistry registry) {
        return registry == INSTANCE;
    }

    private DisabledMetricsRegistry() {
        super(Clock.SYSTEM);
        config().meterFilter(MeterFilter.deny());
    }

    @Override
    protected <T> Gauge newGauge(Meter.Id id, T obj, ToDoubleFunction<T> valueFunction) {
        return new NoopGauge(id);
    }

    @Override
    protected Counter newCounter(Meter.Id id) {
        return new NoopCounter(id);
    }

    @Override
    protected Timer newTimer(Meter.Id id, DistributionStatisticConfig distributionStatisticConfig,
                             PauseDetector pauseDetector) {
        return new NoopTimer(id);
    }

    @Override
    protected DistributionSummary newDistributionSummary(Meter.Id id,
                                                         DistributionStatisticConfig distributionStatisticConfig, double scale) {
        return new NoopDistributionSummary(id);
    }

    @Override
    protected Meter newMeter(Meter.Id id, Meter.Type type, Iterable<Measurement> measurements) {
        return new NoopMeter(id);
    }

    @Override
    protected <T> FunctionTimer newFunctionTimer(Meter.Id id, T obj, ToLongFunction<T> countFunction,
                                                 ToDoubleFunction<T> totalTimeFunction, TimeUnit totalTimeFunctionUnit) {
        return new NoopFunctionTimer(id);
    }

    @Override
    protected <T> FunctionCounter newFunctionCounter(Meter.Id id, T obj, ToDoubleFunction<T> countFunction) {
        return new NoopFunctionCounter(id);
    }

    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.MILLISECONDS;
    }

    @Override
    protected DistributionStatisticConfig defaultHistogramConfig() {
        return DistributionStatisticConfig.DEFAULT;
    }
}
