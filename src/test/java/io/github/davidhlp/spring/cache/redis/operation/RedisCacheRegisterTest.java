package io.github.davidhlp.spring.cache.redis.operation;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.expression.AnnotatedElementKey;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisCacheRegister 单元测试
 *
 * <p>注册/查询以 {@link AnnotatedElementKey}（方法 + 目标类）为查找键；
 * operation 自身的 key 字段不参与 register lookup（那是运行时缓存键的来源）。
 * 测试用 {@link #fixtureMethod()} 与 {@link #otherFixtureMethod()} 作为 elementKey 的方法维度。
 */
@DisplayName("RedisCacheRegister Tests")
class RedisCacheRegisterTest {

    private static final Method METHOD = method("fixtureMethod");
    private static final Method OTHER_METHOD = method("otherFixtureMethod");
    private static final Class<?> TARGET_CLASS = RedisCacheRegisterTest.class;
    private static final AnnotatedElementKey ELEMENT_KEY = new AnnotatedElementKey(METHOD, TARGET_CLASS);
    private static final AnnotatedElementKey OTHER_ELEMENT_KEY =
            new AnnotatedElementKey(OTHER_METHOD, TARGET_CLASS);

    /** 仅供反射获取 Method，无实际用途 */
    void fixtureMethod() {
        // no-op
    }

    /** 仅供反射获取另一个 Method，用于构造不匹配的 elementKey */
    void otherFixtureMethod() {
        // no-op
    }

    private static Method method(String name) {
        try {
            return RedisCacheRegisterTest.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private RedisCacheRegister register;

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("default constructor creates register successfully")
        void defaultConstructor_createsSuccessfully() {
            register = new RedisCacheRegister();

            assertThat(register).isNotNull();
        }

        @Test
        @DisplayName("constructor with custom sizes creates register with custom eviction strategy")
        void customSizes_createsSuccessfully() {
            register = new RedisCacheRegister(100, 50);

            assertThat(register).isNotNull();
        }
    }

    @Nested
    @DisplayName("registerCacheableOperation Tests")
    class RegisterCacheableOperationTests {

        @BeforeEach
        void setUp() {
            register = new RedisCacheRegister(100, 50);
        }

        @Test
        @DisplayName("registerCacheableOperation stores operation for single cache name")
        void registerCacheableOperation_singleCacheName_storesOperation() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testOperation")
                    .cacheNames("cache1")
                    .build();

            register.registerCacheableOperation(METHOD, TARGET_CLASS, operation);

            RedisCacheableOperation result = register.getCacheableOperation("cache1", ELEMENT_KEY);
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("testOperation");
        }

        @Test
        @DisplayName("registerCacheableOperation stores operation for multiple cache names")
        void registerCacheableOperation_multipleCacheNames_storesOperations() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testOperation")
                    .cacheNames("cache1", "cache2")
                    .build();

            register.registerCacheableOperation(METHOD, TARGET_CLASS, operation);

            RedisCacheableOperation result1 = register.getCacheableOperation("cache1", ELEMENT_KEY);
            RedisCacheableOperation result2 = register.getCacheableOperation("cache2", ELEMENT_KEY);

            // 验证两个 cache name 都存了同一个 operation(而非仅非空)
            assertThat(result1).isNotNull();
            assertThat(result1.getName()).isEqualTo("testOperation");
            assertThat(result2).isNotNull();
            assertThat(result2.getName()).isEqualTo("testOperation");
        }

        @Test
        @DisplayName("registerCacheableOperation updates existing operation")
        void registerCacheableOperation_existingKey_updatesOperation() {
            RedisCacheableOperation operation1 = RedisCacheableOperation.builder()
                    .name("operation1")
                    .cacheNames("cache1")
                    .build();

            RedisCacheableOperation operation2 = RedisCacheableOperation.builder()
                    .name("operation2")
                    .cacheNames("cache1")
                    .build();

            register.registerCacheableOperation(METHOD, TARGET_CLASS, operation1);
            register.registerCacheableOperation(METHOD, TARGET_CLASS, operation2);

            RedisCacheableOperation result = register.getCacheableOperation("cache1", ELEMENT_KEY);
            assertThat(result.getName()).isEqualTo("operation2");
        }
    }

    @Nested
    @DisplayName("registerCacheEvictOperation Tests")
    class RegisterCacheEvictOperationTests {

        @BeforeEach
        void setUp() {
            register = new RedisCacheRegister(100, 50);
        }

        @Test
        @DisplayName("registerCacheEvictOperation stores operation for single cache name")
        void registerCacheEvictOperation_singleCacheName_storesOperation() {
            RedisCacheEvictOperation operation = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1")
                    .build();

            register.registerCacheEvictOperation(METHOD, TARGET_CLASS, operation);

            RedisCacheEvictOperation result = register.getCacheEvictOperation("cache1", ELEMENT_KEY);
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("evictOperation");
        }

        @Test
        @DisplayName("registerCacheEvictOperation stores operation for multiple cache names")
        void registerCacheEvictOperation_multipleCacheNames_storesOperations() {
            RedisCacheEvictOperation operation = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1", "cache2")
                    .build();

            register.registerCacheEvictOperation(METHOD, TARGET_CLASS, operation);

            RedisCacheEvictOperation result1 = register.getCacheEvictOperation("cache1", ELEMENT_KEY);
            RedisCacheEvictOperation result2 = register.getCacheEvictOperation("cache2", ELEMENT_KEY);

            // 验证两个 cache name 都存了同一个 evict operation(而非仅非空)
            assertThat(result1).isNotNull();
            assertThat(result1.getName()).isEqualTo("evictOperation");
            assertThat(result2).isNotNull();
            assertThat(result2.getName()).isEqualTo("evictOperation");
        }

        @Test
        @DisplayName("registerCacheEvictOperation updates existing operation")
        void registerCacheEvictOperation_existingKey_updatesOperation() {
            RedisCacheEvictOperation operation1 = RedisCacheEvictOperation.builder()
                    .name("evictOperation1")
                    .cacheNames("cache1")
                    .build();

            RedisCacheEvictOperation operation2 = RedisCacheEvictOperation.builder()
                    .name("evictOperation2")
                    .cacheNames("cache1")
                    .build();

            register.registerCacheEvictOperation(METHOD, TARGET_CLASS, operation1);
            register.registerCacheEvictOperation(METHOD, TARGET_CLASS, operation2);

            RedisCacheEvictOperation result = register.getCacheEvictOperation("cache1", ELEMENT_KEY);
            assertThat(result.getName()).isEqualTo("evictOperation2");
        }
    }

    @Nested
    @DisplayName("getCacheableOperation Tests")
    class GetCacheableOperationTests {

        @BeforeEach
        void setUp() {
            register = new RedisCacheRegister(100, 50);
        }

        @Test
        @DisplayName("getCacheableOperation returns null when operation not found")
        void getCacheableOperation_notFound_returnsNull() {
            RedisCacheableOperation result = register.getCacheableOperation("nonexistent", ELEMENT_KEY);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("getCacheableOperation returns null for non-matching element key")
        void getCacheableOperation_wrongElementKey_returnsNull() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testOperation")
                    .cacheNames("cache1")
                    .build();

            register.registerCacheableOperation(METHOD, TARGET_CLASS, operation);

            RedisCacheableOperation result = register.getCacheableOperation("cache1", OTHER_ELEMENT_KEY);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("getCacheableOperation returns null for wrong cache name")
        void getCacheableOperation_wrongCacheName_returnsNull() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testOperation")
                    .cacheNames("cache1")
                    .build();

            register.registerCacheableOperation(METHOD, TARGET_CLASS, operation);

            RedisCacheableOperation result = register.getCacheableOperation("cache2", ELEMENT_KEY);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getCacheEvictOperation Tests")
    class GetCacheEvictOperationTests {

        @BeforeEach
        void setUp() {
            register = new RedisCacheRegister(100, 50);
        }

        @Test
        @DisplayName("getCacheEvictOperation returns null when operation not found")
        void getCacheEvictOperation_notFound_returnsNull() {
            RedisCacheEvictOperation result = register.getCacheEvictOperation("nonexistent", ELEMENT_KEY);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("getCacheEvictOperation returns null for non-matching element key")
        void getCacheEvictOperation_wrongElementKey_returnsNull() {
            RedisCacheEvictOperation operation = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1")
                    .build();

            register.registerCacheEvictOperation(METHOD, TARGET_CLASS, operation);

            RedisCacheEvictOperation result = register.getCacheEvictOperation("cache1", OTHER_ELEMENT_KEY);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("getCacheEvictOperation returns null for wrong cache name")
        void getCacheEvictOperation_wrongCacheName_returnsNull() {
            RedisCacheEvictOperation operation = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1")
                    .build();

            register.registerCacheEvictOperation(METHOD, TARGET_CLASS, operation);

            RedisCacheEvictOperation result = register.getCacheEvictOperation("cache2", ELEMENT_KEY);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getCachePutOperation Tests")
    class GetCachePutOperationTests {

        @BeforeEach
        void setUp() {
            register = new RedisCacheRegister(100, 50);
        }

        @Test
        @DisplayName("getCachePutOperation stores and retrieves put operation")
        void getCachePutOperation_storesAndRetrieves() {
            RedisCachePutOperation operation = RedisCachePutOperation.builder()
                    .name("putOperation")
                    .cacheNames("cache1")
                    .build();

            register.registerCachePutOperation(METHOD, TARGET_CLASS, operation);

            RedisCachePutOperation result = register.getCachePutOperation("cache1", ELEMENT_KEY);
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("putOperation");
        }

        @Test
        @DisplayName("getCachePutOperation returns null when not found")
        void getCachePutOperation_notFound_returnsNull() {
            RedisCachePutOperation result = register.getCachePutOperation("nonexistent", ELEMENT_KEY);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Cacheable and Evict Operation Isolation Tests")
    class OperationIsolationTests {

        @BeforeEach
        void setUp() {
            register = new RedisCacheRegister(100, 50);
        }

        @Test
        @DisplayName("cacheable and evict operations are stored separately")
        void cacheableAndEvict_storedSeparately() {
            RedisCacheableOperation cacheableOp = RedisCacheableOperation.builder()
                    .name("cacheableOperation")
                    .cacheNames("cache1")
                    .build();

            RedisCacheEvictOperation evictOp = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1")
                    .build();

            register.registerCacheableOperation(METHOD, TARGET_CLASS, cacheableOp);
            register.registerCacheEvictOperation(METHOD, TARGET_CLASS, evictOp);

            RedisCacheableOperation cacheableResult = register.getCacheableOperation("cache1", ELEMENT_KEY);
            RedisCacheEvictOperation evictResult = register.getCacheEvictOperation("cache1", ELEMENT_KEY);

            assertThat(cacheableResult).isNotNull();
            assertThat(cacheableResult.getName()).isEqualTo("cacheableOperation");
            assertThat(evictResult).isNotNull();
            assertThat(evictResult.getName()).isEqualTo("evictOperation");
        }

        @Test
        @DisplayName("same cache name and element key but different types are independent")
        void sameNameKeyDifferentType_areIndependent() {
            RedisCacheableOperation cacheableOp = RedisCacheableOperation.builder()
                    .name("cacheable")
                    .cacheNames("myCache")
                    .build();

            RedisCacheEvictOperation evictOp = RedisCacheEvictOperation.builder()
                    .name("evict")
                    .cacheNames("myCache")
                    .build();

            register.registerCacheableOperation(METHOD, TARGET_CLASS, cacheableOp);
            register.registerCacheEvictOperation(METHOD, TARGET_CLASS, evictOp);

            RedisCacheableOperation cacheableResult = register.getCacheableOperation("myCache", ELEMENT_KEY);
            RedisCacheEvictOperation evictResult = register.getCacheEvictOperation("myCache", ELEMENT_KEY);

            // 验证同 cacheName + elementKey 下,两种类型各自独立存储(而非互相覆盖)
            assertThat(cacheableResult).isNotNull();
            assertThat(cacheableResult.getName()).isEqualTo("cacheable");
            assertThat(evictResult).isNotNull();
            assertThat(evictResult.getName()).isEqualTo("evict");
        }
    }

    @Nested
    @DisplayName("Type Isolation Tests")
    class TypeIsolationTests {

        @BeforeEach
        void setUp() {
            register = new RedisCacheRegister(100, 50);
        }

        @Test
        @DisplayName("getCacheableOperation returns null when slot holds a different operation type")
        void getCacheableOperation_typeMismatch_returnsNull() {
            // 同一 cacheName + elementKey 注册 evict，再用 cacheable 查询：类型不匹配应返回 null
            RedisCacheEvictOperation evictOp = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1")
                    .build();

            register.registerCacheEvictOperation(METHOD, TARGET_CLASS, evictOp);

            RedisCacheableOperation result = register.getCacheableOperation("cache1", ELEMENT_KEY);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("operations with special characters in cache name are handled")
        void operationWithSpecialChars_handledCorrectly() {
            register = new RedisCacheRegister(100, 50);

            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testOperation")
                    .cacheNames("cache:with:colons")
                    .build();

            register.registerCacheableOperation(METHOD, TARGET_CLASS, operation);

            RedisCacheableOperation result =
                    register.getCacheableOperation("cache:with:colons", ELEMENT_KEY);
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("testOperation");
        }

        @Test
        @DisplayName("multiple registrations of same operation increments internal size")
        void multipleRegistrations_incrementsSize() {
            register = new RedisCacheRegister(100, 50);

            for (int i = 0; i < 10; i++) {
                RedisCacheableOperation operation = RedisCacheableOperation.builder()
                        .name("operation" + i)
                        .cacheNames("cache" + i)
                        .build();
                register.registerCacheableOperation(METHOD, TARGET_CLASS, operation);
            }

            // 验证多个注册都独立存入:取 cache0 与 cache5,断言各自 name(而非仅非空)
            RedisCacheableOperation result5 = register.getCacheableOperation("cache5", ELEMENT_KEY);
            RedisCacheableOperation result0 = register.getCacheableOperation("cache0", ELEMENT_KEY);
            assertThat(result5).isNotNull();
            assertThat(result5.getName()).isEqualTo("operation5");
            assertThat(result0).isNotNull();
            assertThat(result0.getName()).isEqualTo("operation0");
        }
    }
}
