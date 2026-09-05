package io.github.davidhlp.spring.cache.redis.cache;




import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult.FailureKind;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

/**
 * Typed failure for a cache write operation that cannot be reported as success.
 *
 * <p>调用方可捕获本异常并按 {@link #getOperation()} / {@link #getFailureKind()} / cause
 * 分流(ADR-07)。GET 失败有意不翻译为本异常 — 读降级为 miss,诊断留在
 * {@code CacheResult}。
 *
 * <p><b>Key 隐私(ADR-06)</b>:本异常<b>不</b>持有/暴露 raw key;message 也不含 raw key —
 * 仅 operation/kind 与 cacheName(配置级低基数)。调用方如需关联具体请求,用 MDC requestId。
 *
 * <p><b>构造限制</b>:构造器 package-private — 仅框架内部(cache writer)可创建;
 * 外部只能捕获与读取,不能伪造。
 */
public final class CacheOperationException extends RuntimeException {

    private final CacheOperation operation;
    private final FailureKind failureKind;
    private final String cacheName;

    CacheOperationException(
            @NonNull CacheOperation operation,
            @Nullable FailureKind failureKind,
            @NonNull String cacheName,
            @Nullable Throwable cause) {
        super(message(operation, failureKind, cacheName), cause);
        this.operation = operation;
        this.failureKind = failureKind;
        this.cacheName = cacheName;
    }

    private static String message(
            CacheOperation operation, FailureKind failureKind, String cacheName) {
        String kind = failureKind == null ? "UNKNOWN" : failureKind.name();
        // 不含 raw key — ADR-06 key 隐私
        return "Cache " + operation + " failed (" + kind + ") for cacheName=" + cacheName;
    }

    /** 失败的操作(typed 枚举)。 */
    @NonNull
    public CacheOperation getOperation() {
        return operation;
    }

    /** 失败分类(typed 枚举)。 */
    @Nullable
    public FailureKind getFailureKind() {
        return failureKind;
    }

    /** 失败的缓存名(配置级低基数,非 raw key)。 */
    @NonNull
    public String getCacheName() {
        return cacheName;
    }

    @Override
    public String toString() {
        return "CacheOperationException{operation=" + operation
                + ", failureKind=" + failureKind
                + ", cacheName=" + cacheName
                + ", cause=" + (getCause() == null ? null : getCause().getClass().getSimpleName())
                + '}';
    }
}
