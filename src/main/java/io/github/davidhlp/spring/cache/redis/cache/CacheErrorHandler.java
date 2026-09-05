package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult.FailureKind;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.serialization.SerializationException;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一的缓存错误处理器 — per-operation 策略调度 deep seam.
 *
 * <p>职责：
 * <ol>
 *   <li>定义 {@link ErrorStrategy} 三态语义（FAIL_FAST / GRACEFUL_DEGRADATION / SILENT）</li>
 *   <li>维护 per-operation 策略表 — 单一事实源</li>
 *   <li>按 operation 调度策略 + 应用策略（日志级别 + CacheResult 形态）</li>
 * </ol>
 *
 * <p>单一入口 {@link #handleError(CacheOperation, String, String, Exception)} +
 * per-operation 策略集中到 {@link #STRATEGIES} 不可变 Map。调用方只需传
 * {@link CacheContext#getOperation() context.getOperation()}，无需记忆具体方法名；
 * 新增 operation 只需在 {@link CacheOperation} 追加枚举值 + 在 {@link #STRATEGIES} 追加一行。
 *
 * <p><b>deletion test</b>：删掉 {@link #STRATEGIES} → 调用方必须自己感知每个 operation 的
 * 策略，per-operation 概念散落，本类的"统一错误处理"语义丢失。删掉
 * {@link #handleError} 方法 → per-operation 调度失去入口。两条路径都让 seam
 * 失去价值 — 真 seam。
 *
 * <p><b>typed failure(ADR-03)</b>:本类产出 {@link CacheResult#failure(CacheOperation, FailureKind, Throwable)}
 * — operation/kind 均为 typed 枚举,失败必须可归类、可分流。
 */
@Slf4j
class CacheErrorHandler {

    /**
     * 错误处理策略。FAIL_FAST 由 Writer 转换为 typed exception；另外两种策略仍返回
     * failure status，区别只在于调用方是否抛出。
     */
    public enum ErrorStrategy {
        FAIL_FAST,
        GRACEFUL_DEGRADATION,
        SILENT
    }

    private static final Map<CacheOperation, ErrorStrategy> STRATEGIES = Map.of(
            CacheOperation.GET, ErrorStrategy.GRACEFUL_DEGRADATION,
            CacheOperation.PUT, ErrorStrategy.FAIL_FAST,
            CacheOperation.PUT_IF_ABSENT, ErrorStrategy.FAIL_FAST,
            CacheOperation.REMOVE, ErrorStrategy.SILENT,
            CacheOperation.CLEAN, ErrorStrategy.FAIL_FAST);

    /**
     * 统一失败指标上报(ADR-06)— null 表示未装配(测试/registry 缺失 → no-op)。
     * 每个失败事件在此唯一出口上报一次,不重复计数。
     */
    private final io.github.davidhlp.spring.cache.redis.cache.CacheFailureReporter failureReporter;

    public CacheErrorHandler() {
        this(null);
    }

    public CacheErrorHandler(
            io.github.davidhlp.spring.cache.redis.cache.CacheFailureReporter failureReporter) {
        this.failureReporter = failureReporter;
    }

    /**
     * 按 operation 调度错误策略并保留诊断信息(typed kind)。
     */
    public CacheResult handleError(CacheOperation operation, String cacheName, String key, Exception e) {
        ErrorStrategy strategy = operation == null
                ? ErrorStrategy.FAIL_FAST
                : STRATEGIES.getOrDefault(operation, ErrorStrategy.FAIL_FAST);
        return handleException(operation, cacheName, key, e, strategy, classify(e));
    }

    /**
     * 按 operation 调度错误策略,使用调用方提供的 typed kind(partial-clean 等场景)。
     */
    CacheResult handleError(
            CacheOperation operation,
            String cacheName,
            String key,
            FailureKind failureKind,
            Exception e) {
        ErrorStrategy strategy = operation == null
                ? ErrorStrategy.FAIL_FAST
                : STRATEGIES.getOrDefault(operation, ErrorStrategy.FAIL_FAST);
        return handleException(operation, cacheName, key, e, strategy, failureKind);
    }

    /**
     * 直接应用指定策略,供测试和显式内部调用使用。
     */
    public CacheResult handleException(
            CacheOperation operation,
            String cacheName,
            String key,
            Exception e,
            ErrorStrategy strategy) {
        return handleException(operation, cacheName, key, e, strategy, classify(e));
    }

    private CacheResult handleException(
            CacheOperation operation,
            String cacheName,
            String key,
            Exception e,
            ErrorStrategy strategy,
            FailureKind failureKind) {
        CacheResult result = CacheResult.failure(operation, failureKind, e);
        String operationName = operation == null ? "UNKNOWN" : operation.name();
        // ADR-06:每次失败恰好一次统一指标(operation/kind/strategy 有限枚举 tag)
        if (failureReporter != null) {
            failureReporter.report(operation, failureKind, strategy);
        }
        return switch (strategy) {
            case FAIL_FAST -> {
                // ADR-06 key 隐私:ERROR 不打印 raw key / 异常 message(可能嵌 key);
                // 完整栈(含 cause message)仅留 DEBUG 供开发诊断
                log.error("Cache {} failed: cacheName={}, kind={}, cause={}",
                        operationName, cacheName, failureKind,
                        e == null ? "null" : e.getClass().getSimpleName());
                log.debug("Cache {} failure detail: cacheName={}, kind={}",
                        operationName, cacheName, failureKind, e);
                yield result;
            }
            case GRACEFUL_DEGRADATION -> {
                // key 隐私:WARN 不打印 raw key / exception message(ADR-06)
                log.warn("Cache {} failed, degrading to miss: cacheName={}, kind={}, cause={}",
                        operationName, cacheName, failureKind,
                        e == null ? "null" : e.getClass().getSimpleName());
                yield result;
            }
            case SILENT -> {
                log.warn("Cache {} failed, best-effort removal continues: cacheName={}, kind={}, cause={}",
                        operationName, cacheName, failureKind,
                        e == null ? "null" : e.getClass().getSimpleName());
                yield result;
            }
        };
    }

    /**
     * 把底层异常分类为 typed {@link FailureKind}。
     */
    private FailureKind classify(Exception e) {
        if (e instanceof SerializationException) {
            return FailureKind.SERIALIZATION;
        }
        if (e instanceof CancellationException || e instanceof InterruptedException) {
            return FailureKind.CANCELLATION;
        }
        if (e instanceof TimeoutException) {
            return FailureKind.TIMEOUT;
        }
        return FailureKind.REDIS;
    }
}
