package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.cache.CacheErrorHandler.ErrorStrategy;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult.FailureKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内部失败上报单点— 不进入 public allowlist。
 *
 * <p>唯一指标 {@code resicache.cache.failure},tag 仅低基数有限枚举:
 * {@code operation}({@link CacheOperation})、{@code kind}({@link FailureKind})、
 * {@code strategy}({@link ErrorStrategy})。禁止 cacheName / key / message tag
 * (高基数),WARN/ERROR 日志与异常消息默认不含 raw key(由调用方保证)。
 *
 * <p>计数去重:由 writer PUT chain 产生的写回失败只经本类一次上报 — 链内
 * {@code CacheErrorHandler} 是唯一报告出口;{@code LoaderOrchestrator} 只保留脱敏 WARN
 * 与 {@code LoadedWithWriteBackFailure},不重复计数。
 *
 * <p><b>边界</b>:本指标只统计<b>缓存操作失败</b>(GET degrade / 写 fail-fast /
 * REMOVE best-effort / write-back failure)。Bloom 过滤器底层 Redis 位操作的
 * {@code bloomsift.*} counter 与 fail-open 路径({@code BloomSupport} 吞异常放行 loader)
 * <b>不属于</b>本指标 — fail-open 是成功的保护行为而非缓存失败,误报会污染降级告警。
 * 二者刻意分离:filter 级 telemetry 在 adapter,缓存级失败在本 reporter。
 *
 * <p>registry 由 {@link ResolvedMetrics} 单一决议,永不为 null;metrics 未启用时它是
 * no-op seam,本 reporter 全程无副作用,与 ResiCache 其余 metrics 行为一致。
 * counter map 按 (operation, kind, strategy) 组合惰性注册 — tag 组合有界
 * (5 ops × 5 kinds × 3 strategies),cardinality 可控。
 */
final class CacheFailureReporter {

    /** 统一失败指标名 — 低基数 failure counter 唯一名字。 */
    public static final String METRIC_NAME = "resicache.cache.failure";

    private final MeterRegistry registry;
    /** 关闭路径唯一判据 —— 构造期从 seam 推导一次。 */
    private final boolean disabled;
    private final ConcurrentMap<FailureKey, Counter> counters = new ConcurrentHashMap<>();

    public CacheFailureReporter(MeterRegistry registry) {
        this.registry = registry;
        this.disabled = DisabledMetricsRegistry.isDisabledSeam(registry);
    }

    /**
     * 上报一次失败事件 — 每事件恰好调用一次。
     *
     * @param operation 失败操作(可为 null → UNKNOWN tag)
     * @param kind      失败分类(可为 null → UNKNOWN tag)
     * @param strategy  错误处理策略(可为 null → UNKNOWN tag)
     */
    public void report(@org.springframework.lang.Nullable CacheOperation operation,
                       @org.springframework.lang.Nullable FailureKind kind,
                       @org.springframework.lang.Nullable ErrorStrategy strategy) {
        if (disabled) {
            // 关闭路径:跳过 FailureKey 构造、map 查找与 NoopCounter。
            return;
        }
        FailureKey key = new FailureKey(
                operation == null ? "UNKNOWN" : operation.name(),
                kind == null ? "UNKNOWN" : kind.name(),
                strategy == null ? "UNKNOWN" : strategy.name());
        Counter counter = counters.computeIfAbsent(key, this::register);
        counter.increment();
    }

    private Counter register(FailureKey key) {
        return Counter.builder(METRIC_NAME)
                .description("Cache operation failures, tagged by finite-enum operation/kind/strategy "
                        + "(low cardinality; no cacheName/key/message tags)")
                .tags("operation", key.operation(),
                        "kind", key.kind(),
                        "strategy", key.strategy())
                .register(registry);
    }

    /** 测试用：暴露当前已注册的 counter 数。 */
    int registeredCounterCount() {
        return counters.size();
    }

    private record FailureKey(String operation, String kind, String strategy) {
    }
}
