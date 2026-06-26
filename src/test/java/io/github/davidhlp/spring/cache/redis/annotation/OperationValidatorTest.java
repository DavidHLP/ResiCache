package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.cache.interceptor.CacheOperation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OperationValidator 单元测试
 *
 * <p>覆盖三条违例路径(key/keyGenerator 互斥、cacheManager/cacheResolver 互斥、cacheNames 空)
 * 与一条合法路径。纯校验逻辑,无 Spring/testcontainers 依赖。这是 C05 抽出 OperationValidator
 * 的直接单测收益(原本埋在 565 行 god class 内,无法独立断言)。
 */
@DisplayName("OperationValidator Tests")
class OperationValidatorTest {

    private final OperationValidator validator = new OperationValidator();
    private final Object target = this;

    /** 构造一个带指定字段的 CacheOperation(其余字段为空,模拟最小合法/非法配置) */
    private CacheOperation opWith(String key, String keyGenerator,
                                  String cacheManager, String cacheResolver,
                                  String... cacheNames) {
        RedisCacheableOperation.Builder builder = RedisCacheableOperation.builder().name("test");
        if (cacheNames != null && cacheNames.length > 0) {
            builder.cacheNames(cacheNames);
        }
        if (key != null) {
            builder.key(key);
        }
        if (keyGenerator != null) {
            builder.keyGenerator(keyGenerator);
        }
        if (cacheManager != null) {
            builder.cacheManager(cacheManager);
        }
        if (cacheResolver != null) {
            builder.cacheResolver(cacheResolver);
        }
        return builder.build();
    }

    @Test
    @DisplayName("valid configuration passes validation")
    void validate_validConfig_passes() {
        CacheOperation op = opWith("myKey", null, null, null, "cache1");

        assertThatCode(() -> validator.validate(target, op)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects when both key and keyGenerator are set")
    void validate_keyAndKeyGenerator_throws() {
        CacheOperation op = opWith("myKey", "myKeyGenerator", null, null, "cache1");

        assertThatThrownBy(() -> validator.validate(target, op))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key")
                .hasMessageContaining("keyGenerator");
    }

    @Test
    @DisplayName("rejects when both cacheManager and cacheResolver are set")
    void validate_cacheManagerAndCacheResolver_throws() {
        CacheOperation op = opWith(null, null, "myCacheManager", "myCacheResolver", "cache1");

        assertThatThrownBy(() -> validator.validate(target, op))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cacheManager")
                .hasMessageContaining("cacheResolver");
    }

    @Test
    @DisplayName("rejects when no cache name is specified")
    void validate_emptyCacheNames_throws() {
        CacheOperation op = opWith(null, null, null, null);

        assertThatThrownBy(() -> validator.validate(target, op))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cache name");
    }
}
