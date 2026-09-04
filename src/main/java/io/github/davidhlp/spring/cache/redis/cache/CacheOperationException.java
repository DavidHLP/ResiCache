package io.github.davidhlp.spring.cache.redis.cache;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

/**
 * Typed failure for a cache write operation that cannot be reported as success.
 *
 * <p>The original Redis cause is retained as the exception cause. GET failures
 * are intentionally not translated to this exception; reads degrade to a miss
 * while retaining their diagnostic {@code CacheResult}.
 */
final class CacheOperationException extends RuntimeException {

    private final String operation;
    private final String failureKind;
    private final String cacheName;
    private final String key;

    CacheOperationException(
            @NonNull String operation,
            @Nullable String failureKind,
            @NonNull String cacheName,
            @NonNull String key,
            @Nullable Throwable cause) {
        super(message(operation, failureKind, cacheName, key), cause);
        this.operation = operation;
        this.failureKind = failureKind;
        this.cacheName = cacheName;
        this.key = key;
    }

    private static String message(
            String operation, String failureKind, String cacheName, String key) {
        String kind = failureKind == null ? "UNKNOWN" : failureKind;
        return "Cache " + operation + " failed (" + kind + ") for cacheName="
                + cacheName + ", key=" + key;
    }

    public String getOperation() {
        return operation;
    }

    @Nullable
    public String getFailureKind() {
        return failureKind;
    }

    public String getCacheName() {
        return cacheName;
    }

    public String getKey() {
        return key;
    }
}
