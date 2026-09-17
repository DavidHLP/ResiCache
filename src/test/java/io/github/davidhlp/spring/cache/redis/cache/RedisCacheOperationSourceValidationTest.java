package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.interceptor.CacheOperation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RedisCacheOperationSource validation seam tests.
 */
@DisplayName("RedisCacheOperationSource validation")
class RedisCacheOperationSourceValidationTest {

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

    private RedisCacheOperationSource sourceFor(CacheOperation operation) {
        AnnotationParser parser = new AnnotationParser() {
            @Override
            ParsedAnnotations parse(Object target) {
                return new ParsedAnnotations(List.of(operation), List.of());
            }
        };
        return new RedisCacheOperationSource(
                RedisProCacheProperties.NativeAnnotationMode.SELECTIVE, parser, null);
    }

    private Method targetMethod() {
        try {
            return RedisCacheOperationSourceValidationTest.class.getDeclaredMethod("targetMethod");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused")
    private void targetMethod() {
    }

    @Test
    @DisplayName("valid configuration passes validation")
    void validate_validConfig_passes() {
        CacheOperation op = opWith("myKey", null, null, null, "cache1");

        assertThatCode(() -> sourceFor(op).findCacheOperations(targetMethod()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects when both key and keyGenerator are set")
    void validate_keyAndKeyGenerator_throws() {
        CacheOperation op = opWith("myKey", "myKeyGenerator", null, null, "cache1");

        assertThatThrownBy(() -> sourceFor(op).findCacheOperations(targetMethod()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key")
                .hasMessageContaining("keyGenerator");
    }

    @Test
    @DisplayName("rejects when both cacheManager and cacheResolver are set")
    void validate_cacheManagerAndCacheResolver_throws() {
        CacheOperation op = opWith(null, null, "myCacheManager", "myCacheResolver", "cache1");

        assertThatThrownBy(() -> sourceFor(op).findCacheOperations(targetMethod()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cacheManager")
                .hasMessageContaining("cacheResolver");
    }

    @Test
    @DisplayName("rejects when no cache name is specified")
    void validate_emptyCacheNames_throws() {
        CacheOperation op = opWith(null, null, null, null);

        assertThatThrownBy(() -> sourceFor(op).findCacheOperations(targetMethod()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cache name");
    }
}
