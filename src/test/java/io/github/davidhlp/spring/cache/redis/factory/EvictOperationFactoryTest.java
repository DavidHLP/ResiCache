package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheEvictOperation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EvictOperationFactory 单元测试
 *
 * <p>覆盖 create() 的字段映射(cacheNames/key/allEntries/beforeInvocation 等)与 supports() 判定。
 * 纯 builder 逻辑,无 Spring/testcontainers 依赖。
 */
@DisplayName("EvictOperationFactory Tests")
class EvictOperationFactoryTest {

    @RedisCacheEvict(cacheNames = "c1", key = "k1", allEntries = true, beforeInvocation = true)
    public void evictMethod() { }

    private final EvictOperationFactory factory = new EvictOperationFactory();
    private Method method;
    private RedisCacheEvict annotation;

    @BeforeEach
    void setUp() throws Exception {
        method = getClass().getMethod("evictMethod");
        annotation = method.getAnnotation(RedisCacheEvict.class);
    }

    @Test
    @DisplayName("create builds operation with mapped fields")
    void create_buildsOperationWithFields() {
        RedisCacheEvictOperation op = factory.create(method, annotation, this, null, "k1");

        assertThat(op).isNotNull();
        assertThat(op.getName()).isEqualTo("evictMethod");
        assertThat(op.getKey()).isEqualTo("k1");
        assertThat(op.getCacheNames()).containsExactly("c1");
        assertThat(op.isCacheWide()).isTrue();
        assertThat(op.isBeforeInvocation()).isTrue();
    }

    @Test
    @DisplayName("create uses provided key override")
    void create_usesProvidedKey() {
        RedisCacheEvictOperation op = factory.create(method, annotation, this, null, "custom-key");

        assertThat(op.getKey()).isEqualTo("custom-key");
    }

    @Test
    @DisplayName("supports returns true for RedisCacheEvict annotation")
    void supports_redisCacheEvict_returnsTrue() {
        assertThat(factory.supports(annotation)).isTrue();
    }

    @Test
    @DisplayName("supports returns false for unrelated annotation")
    void supports_unrelatedAnnotation_returnsFalse() {
        // Deprecated 不是 RedisCacheEvict → supports false
        Deprecated deprecated = AnnotatedClass.class.getAnnotation(Deprecated.class);

        assertThat(factory.supports(deprecated)).isFalse();
    }

    @Deprecated
    static class AnnotatedClass { }
}
