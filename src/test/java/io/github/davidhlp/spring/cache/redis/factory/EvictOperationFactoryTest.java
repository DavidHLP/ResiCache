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
 * <p>覆盖 create() 的字段映射(cacheNames/key/allEntries/beforeInvocation 等)。纯 builder
 * 逻辑,无 Spring/testcontainers 依赖。
 *
 * <p>ADR-0028:删除 supports() 测试块(main 零调用的死方法不再断言);create 调用跟随接口
 * 签名窄化为 3 参(method, annotation, key)。
 */
@DisplayName("EvictOperationFactory Tests")
class EvictOperationFactoryTest {

    @RedisCacheEvict(cacheNames = "c1", key = "k1", allEntries = true, beforeInvocation = true)
    public void evictMethod() { }

    private final RedisCacheAttributesProjector projector = new RedisCacheAttributesProjector();
    private final EvictOperationFactory factory = new EvictOperationFactory(projector);
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
        RedisCacheEvictOperation op = factory.create(method, annotation, "k1");

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
        RedisCacheEvictOperation op = factory.create(method, annotation, "custom-key");

        assertThat(op.getKey()).isEqualTo("custom-key");
    }
}
