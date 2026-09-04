package io.github.davidhlp.spring.cache.redis.chain.handler;

import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

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
 */
@Slf4j
public class CacheErrorHandler {

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
     * 按 operation 调度错误策略并保留诊断信息。
     */
    public CacheResult handleError(CacheOperation operation, String cacheName, String key, Exception e) {
        ErrorStrategy strategy = operation == null
                ? ErrorStrategy.FAIL_FAST
                : STRATEGIES.getOrDefault(operation, ErrorStrategy.FAIL_FAST);
        String operationName = operation == null ? "UNKNOWN" : operation.name();
        return handleException(operationName, cacheName, key, e, strategy, classify(e));
    }

    /**
     * CLEAN 等调用方需要补充 partial-clean 语义时使用的包内入口。
     */
    CacheResult handleError(
            CacheOperation operation,
            String cacheName,
            String key,
            String failureKind,
            Exception e) {
        ErrorStrategy strategy = operation == null
                ? ErrorStrategy.FAIL_FAST
                : STRATEGIES.getOrDefault(operation, ErrorStrategy.FAIL_FAST);
        String operationName = operation == null ? "UNKNOWN" : operation.name();
        return handleException(operationName, cacheName, key, e, strategy, failureKind);
    }

    /**
     * 直接应用指定策略，供测试和显式内部调用使用。
     */
    public CacheResult handleException(
            String operation,
            String cacheName,
            String key,
            Exception e,
            ErrorStrategy strategy) {
        return handleException(operation, cacheName, key, e, strategy, classify(e));
    }

    private CacheResult handleException(
            String operation,
            String cacheName,
            String key,
            Exception e,
            ErrorStrategy strategy,
            String failureKind) {
        CacheResult result = CacheResult.failure(operation, failureKind, e);
        return switch (strategy) {
            case FAIL_FAST -> {
                log.error("Cache {} failed: cacheName={}, key={}, kind={}",
                        operation, cacheName, key, failureKind, e);
                yield result;
            }
            case GRACEFUL_DEGRADATION -> {
                log.warn("Cache {} failed, degrading to miss: cacheName={}, key={}, kind={}, error={}",
                        operation, cacheName, key, failureKind, e.getMessage());
                yield result;
            }
            case SILENT -> {
                log.warn("Cache {} failed, best-effort removal continues: cacheName={}, key={}, kind={}, error={}",
                        operation, cacheName, key, failureKind, e.getMessage());
                yield result;
            }
        };
    }

    private String classify(Exception e) {
        if (e instanceof io.github.davidhlp.spring.cache.redis.serialization.SerializationException) {
            return "SERIALIZATION";
        }
        if (e instanceof java.util.concurrent.CancellationException
                || e instanceof InterruptedException) {
            return "CANCELLATION";
        }
        if (e instanceof java.util.concurrent.TimeoutException) {
            return "TIMEOUT";
        }
        return "REDIS";
    }
}
