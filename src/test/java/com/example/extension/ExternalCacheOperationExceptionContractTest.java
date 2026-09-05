package com.example.extension;




import io.github.davidhlp.spring.cache.redis.cache.CacheOperationException;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 外部包 typed-exception 契约测试(ADR-07) — 外部扩展方可捕获并按 operation/kind 分流。
 *
 * <p>从 {@code com.example.extension}(框架根包之外)验证:
 * <ul>
 *   <li>{@link CacheOperationException} 是 public final,可捕获</li>
 *   <li>getOperation()/getFailureKind()/cause 可用(typed 枚举)</li>
 *   <li>message/toString 不含 raw key(ADR-06 隐私)</li>
 *   <li>构造器不可从外部访问(不能伪造)</li>
 * </ul>
 */
@DisplayName("External CacheOperationException Contract")
class ExternalCacheOperationExceptionContractTest {

    @Test
    @DisplayName("外部包可捕获 CacheOperationException 并按 typed operation/kind/cause 分流")
    void external_canCatchAndReadTypedFields() {
        assertThatThrownBy(() -> {
            throw newFailure(CacheOperation.PUT, CacheResult.FailureKind.REDIS,
                    "cache", new IllegalStateException("redis down"));
        })
                .isInstanceOf(CacheOperationException.class)
                .satisfies(thrown -> {
                    CacheOperationException ex = (CacheOperationException) thrown;
                    assertThat(ex.getOperation()).isEqualTo(CacheOperation.PUT);
                    assertThat(ex.getFailureKind()).isEqualTo(CacheResult.FailureKind.REDIS);
                    assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class);
                    assertThat(ex.getCacheName()).isEqualTo("cache");
                });
    }

    @Test
    @DisplayName("message 与 toString 不含 raw key(key 隐私)")
    void messageAndToString_omitRawKey() {
        assertThatThrownBy(() -> {
            throw newFailure(CacheOperation.PUT, CacheResult.FailureKind.REDIS,
                    "cache", new IllegalStateException("raw-secret-key-value failure"));
        })
                .isInstanceOf(CacheOperationException.class)
                .satisfies(thrown -> {
                    CacheOperationException ex = (CacheOperationException) thrown;
                    assertThat(ex.getMessage())
                            .as("异常 message 不得含 raw key")
                            .doesNotContain("raw-secret-key-value");
                    assertThat(ex.toString())
                            .as("toString 不得含 raw key / cause message")
                            .doesNotContain("raw-secret-key-value");
                    // cause 的 message 文本不得出现在 toString(只暴露 cause 类型名)
                    assertThat(ex.toString()).doesNotContain("value failure");
                });
    }

    @Test
    @DisplayName("构造器 package-private — 外部包无法 new CacheOperationException")
    void constructor_notAccessibleFromExternal() {
        assertThat(CacheOperationException.class.getConstructors())
                .as("public 构造器必须为空(构造仅限框架内部)")
                .isEmpty();
        assertThat(java.lang.reflect.Modifier.isFinal(CacheOperationException.class.getModifiers()))
                .as("异常必须是 final")
                .isTrue();
        assertThat(RuntimeException.class.isAssignableFrom(CacheOperationException.class))
                .as("必须是 RuntimeException(运行时 typed failure)")
                .isTrue();
    }

    /** Internal construction is reached reflectively so this test remains an external-package contract. */
    private static CacheOperationException newFailure(
            CacheOperation operation, CacheResult.FailureKind kind,
            String cacheName, Throwable cause) {
        try {
            Constructor<CacheOperationException> constructor = CacheOperationException.class
                    .getDeclaredConstructor(CacheOperation.class, CacheResult.FailureKind.class,
                            String.class, Throwable.class);
            constructor.setAccessible(true);
            return constructor.newInstance(operation, kind, cacheName, cause);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
