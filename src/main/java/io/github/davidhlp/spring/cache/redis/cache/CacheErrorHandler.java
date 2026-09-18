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
 * <p><b>typed failure contract</b>:本类产出 {@link CacheResult#failure(CacheOperation, FailureKind, Throwable)}
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
     * 查询某 operation 的错误策略 —— {@link #STRATEGIES} 的唯一读取点。
     *
     * <p>package-private static:策略是 operation 的静态知识(表本身即 static),调用方
     * 只需策略、不需要本类的日志/指标副作用时直接读表 ——
     * {@link #finalizeFailure} 据此决定 FAIL_FAST 抛出 vs 其余记录并继续,
     * 不再自行硬编码「哪个 operation 只 WARN」。指标上报仍只发生在
     * {@link #handleError}(即链内唯一失败出口),本方法不产生任何副作用。
     *
     * @param operation 操作类型(null → FAIL_FAST,保守)
     * @return 该 operation 的错误策略
     */
    static ErrorStrategy strategyFor(CacheOperation operation) {
        return operation == null
                ? ErrorStrategy.FAIL_FAST
                : STRATEGIES.getOrDefault(operation, ErrorStrategy.FAIL_FAST);
    }
    /**
     * 完成 writer 侧的不可变失败结果：FAIL_FAST 抛 typed exception，其余策略记录并继续。
     *
     * <p>这里是策略表的唯一最终化入口。链内 {@link #handleError} 已完成失败分类、
     * 计数与 {@link CacheResult} 构造；本方法只消费结果，不重复上报指标。
     *
     * @param operation 失败的缓存操作
     * @param cacheName 缓存名称
     * @param result 链返回的不可变结果
     */
    static void finalizeFailure(
            CacheOperation operation, String cacheName, CacheResult result) {
        if (result == null || result.isSuccess()) {
            return;
        }
        if (strategyFor(operation) != ErrorStrategy.FAIL_FAST) {
            log.warn("Cache {} failed; continuing best-effort: cacheName={}, kind={}, cause={}",
                    operation,
                    cacheName,
                    result.failureKind(),
                    FailureDiagnostics.sanitizedFailure(result.cause()));
            return;
        }
        throw new CacheOperationException(
                operation,
                result.failureKind(),
                cacheName,
                result.cause());
    }


    /**
     * 统一失败指标上报 contract— null 表示未装配(测试/registry 缺失 → no-op)。
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
        return handleException(operation, cacheName, key, e, strategyFor(operation), classify(e));
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
        return handleException(operation, cacheName, key, e, strategyFor(operation), failureKind);
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
        // Failure-metrics contract:每次失败恰好一次统一指标(operation/kind/strategy 有限枚举 tag)
        if (failureReporter != null) {
            failureReporter.report(operation, failureKind, strategy);
        }
        return switch (strategy) {
            case FAIL_FAST -> {
                // Key-privacy contract:ERROR 不打印 raw key / 异常 message(可能嵌 key);
                // 完整栈(含 cause message)仅留 DEBUG 供开发诊断
                log.error("Cache {} failed: cacheName={}, kind={}, cause={}",
                        operationName, cacheName, failureKind,
                        e == null ? "null" : e.getClass().getSimpleName());
                log.debug("Cache {} failure detail: cacheName={}, kind={}",
                        operationName, cacheName, failureKind, e);
                yield result;
            }
            case GRACEFUL_DEGRADATION -> {
                // Key-privacy contract:WARN 不打印 raw key / exception message
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
