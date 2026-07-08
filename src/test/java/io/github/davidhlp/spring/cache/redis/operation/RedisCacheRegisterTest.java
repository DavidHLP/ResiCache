package io.github.davidhlp.spring.cache.redis.operation;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.expression.AnnotatedElementKey;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisCacheRegister 单元测试 —— ADR-0059 / Round 45 收敛后形态。
 *
 * <p>原 3 对 register/get 公开方法(registerCacheableOperation / registerCacheEvictOperation /
 * registerCachePutOperation + 对应 get)已收敛为单一 {@link RedisCacheRegister#register} +
 * {@link RedisCacheRegister#get} 方法对,通过 {@link OperationKind} 枚举区分命名空间。
 *
 * <p>注册/查询以 {@link AnnotatedElementKey}(方法 + 目标类)为查找键;
 * operation 自身的 key 字段不参与 register lookup(那是运行时缓存键的来源)。
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
    @DisplayName("register(CACHEABLE) Tests")
    class RegisterCacheableTests {

        @BeforeEach
        void setUp() {
            register = new RedisCacheRegister(100, 50);
        }

        @Test
        @DisplayName("register stores operation for single cache name")
        void register_singleCacheName_storesOperation() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testOperation")
                    .cacheNames("cache1")
                    .build();

            register.register(METHOD, TARGET_CLASS, operation, OperationKind.CACHEABLE);

            RedisCacheableOperation result = register.get("cache1", ELEMENT_KEY, OperationKind.CACHEABLE);
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("testOperation");
        }

        @Test
        @DisplayName("register stores operation for multiple cache names")
        void register_multipleCacheNames_storesOperations() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testOperation")
                    .cacheNames("cache1", "cache2")
                    .build();

            register.register(METHOD, TARGET_CLASS, operation, OperationKind.CACHEABLE);

            RedisCacheableOperation result1 = register.get("cache1", ELEMENT_KEY, OperationKind.CACHEABLE);
            RedisCacheableOperation result2 = register.get("cache2", ELEMENT_KEY, OperationKind.CACHEABLE);

            assertThat(result1).isNotNull();
            assertThat(result1.getName()).isEqualTo("testOperation");
            assertThat(result2).isNotNull();
            assertThat(result2.getName()).isEqualTo("testOperation");
        }

        @Test
        @DisplayName("register updates existing operation")
        void register_existingKey_updatesOperation() {
            RedisCacheableOperation operation1 = RedisCacheableOperation.builder()
                    .name("operation1")
                    .cacheNames("cache1")
                    .build();

            RedisCacheableOperation operation2 = RedisCacheableOperation.builder()
                    .name("operation2")
                    .cacheNames("cache1")
                    .build();

            register.register(METHOD, TARGET_CLASS, operation1, OperationKind.CACHEABLE);
            register.register(METHOD, TARGET_CLASS, operation2, OperationKind.CACHEABLE);

            RedisCacheableOperation result = register.get("cache1", ELEMENT_KEY, OperationKind.CACHEABLE);
            assertThat(result.getName()).isEqualTo("operation2");
        }
    }

    @Nested
    @DisplayName("register(CACHE_EVICT) Tests")
    class RegisterCacheEvictTests {

        @BeforeEach
        void setUp() {
            register = new RedisCacheRegister(100, 50);
        }

        @Test
        @DisplayName("register stores operation for single cache name")
        void register_singleCacheName_storesOperation() {
            RedisCacheEvictOperation operation = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1")
                    .build();

            register.register(METHOD, TARGET_CLASS, operation, OperationKind.CACHE_EVICT);

            RedisCacheEvictOperation result = register.get("cache1", ELEMENT_KEY, OperationKind.CACHE_EVICT);
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("evictOperation");
        }

        @Test
        @DisplayName("register stores operation for multiple cache names")
        void register_multipleCacheNames_storesOperations() {
            RedisCacheEvictOperation operation = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1", "cache2")
                    .build();

            register.register(METHOD, TARGET_CLASS, operation, OperationKind.CACHE_EVICT);

            RedisCacheEvictOperation result1 = register.get("cache1", ELEMENT_KEY, OperationKind.CACHE_EVICT);
            RedisCacheEvictOperation result2 = register.get("cache2", ELEMENT_KEY, OperationKind.CACHE_EVICT);

            assertThat(result1).isNotNull();
            assertThat(result1.getName()).isEqualTo("evictOperation");
            assertThat(result2).isNotNull();
            assertThat(result2.getName()).isEqualTo("evictOperation");
        }

        @Test
        @DisplayName("register updates existing operation")
        void register_existingKey_updatesOperation() {
            RedisCacheEvictOperation operation1 = RedisCacheEvictOperation.builder()
                    .name("evictOperation1")
                    .cacheNames("cache1")
                    .build();

            RedisCacheEvictOperation operation2 = RedisCacheEvictOperation.builder()
                    .name("evictOperation2")
                    .cacheNames("cache1")
                    .build();

            register.register(METHOD, TARGET_CLASS, operation1, OperationKind.CACHE_EVICT);
            register.register(METHOD, TARGET_CLASS, operation2, OperationKind.CACHE_EVICT);

            RedisCacheEvictOperation result = register.get("cache1", ELEMENT_KEY, OperationKind.CACHE_EVICT);
            assertThat(result.getName()).isEqualTo("evictOperation2");
        }
    }

    @Nested
    @DisplayName("get(CACHEABLE) Tests")
    class GetCacheableTests {

        @BeforeEach
        void setUp() {
            register = new RedisCacheRegister(100, 50);
        }

        @Test
        @DisplayName("get returns null when operation not found")
        void get_notFound_returnsNull() {
            RedisCacheableOperation result = register.get("nonexistent", ELEMENT_KEY, OperationKind.CACHEABLE);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("get returns null for non-matching element key")
        void get_wrongElementKey_returnsNull() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testOperation")
                    .cacheNames("cache1")
                    .build();

            register.register(METHOD, TARGET_CLASS, operation, OperationKind.CACHEABLE);

            RedisCacheableOperation result = register.get("cache1", OTHER_ELEMENT_KEY, OperationKind.CACHEABLE);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("get returns null for wrong cache name")
        void get_wrongCacheName_returnsNull() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testOperation")
                    .cacheNames("cache1")
                    .build();

            register.register(METHOD, TARGET_CLASS, operation, OperationKind.CACHEABLE);

            RedisCacheableOperation result = register.get("cache2", ELEMENT_KEY, OperationKind.CACHEABLE);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("get(CACHE_EVICT) Tests")
    class GetCacheEvictTests {

        @BeforeEach
        void setUp() {
            register = new RedisCacheRegister(100, 50);
        }

        @Test
        @DisplayName("get returns null when operation not found")
        void get_notFound_returnsNull() {
            RedisCacheEvictOperation result = register.get("nonexistent", ELEMENT_KEY, OperationKind.CACHE_EVICT);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("get returns null for non-matching element key")
        void get_wrongElementKey_returnsNull() {
            RedisCacheEvictOperation operation = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1")
                    .build();

            register.register(METHOD, TARGET_CLASS, operation, OperationKind.CACHE_EVICT);

            RedisCacheEvictOperation result = register.get("cache1", OTHER_ELEMENT_KEY, OperationKind.CACHE_EVICT);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("get returns null for wrong cache name")
        void get_wrongCacheName_returnsNull() {
            RedisCacheEvictOperation operation = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1")
                    .build();

            register.register(METHOD, TARGET_CLASS, operation, OperationKind.CACHE_EVICT);

            RedisCacheEvictOperation result = register.get("cache2", ELEMENT_KEY, OperationKind.CACHE_EVICT);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("get(CACHE_PUT) Tests")
    class GetCachePutTests {

        @BeforeEach
        void setUp() {
            register = new RedisCacheRegister(100, 50);
        }

        @Test
        @DisplayName("get stores and retrieves put operation")
        void get_storesAndRetrieves() {
            RedisCachePutOperation operation = RedisCachePutOperation.builder()
                    .name("putOperation")
                    .cacheNames("cache1")
                    .build();

            register.register(METHOD, TARGET_CLASS, operation, OperationKind.CACHE_PUT);

            RedisCachePutOperation result = register.get("cache1", ELEMENT_KEY, OperationKind.CACHE_PUT);
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("putOperation");
        }

        @Test
        @DisplayName("get returns null when not found")
        void get_notFound_returnsNull() {
            RedisCachePutOperation result = register.get("nonexistent", ELEMENT_KEY, OperationKind.CACHE_PUT);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Operation Kind Isolation Tests")
    class OperationKindIsolationTests {

        @BeforeEach
        void setUp() {
            register = new RedisCacheRegister(100, 50);
        }

        @Test
        @DisplayName("cacheable and evict operations are stored separately by kind")
        void cacheableAndEvict_storedSeparatelyByKind() {
            RedisCacheableOperation cacheableOp = RedisCacheableOperation.builder()
                    .name("cacheableOperation")
                    .cacheNames("cache1")
                    .build();

            RedisCacheEvictOperation evictOp = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1")
                    .build();

            register.register(METHOD, TARGET_CLASS, cacheableOp, OperationKind.CACHEABLE);
            register.register(METHOD, TARGET_CLASS, evictOp, OperationKind.CACHE_EVICT);

            RedisCacheableOperation cacheableResult = register.get("cache1", ELEMENT_KEY, OperationKind.CACHEABLE);
            RedisCacheEvictOperation evictResult = register.get("cache1", ELEMENT_KEY, OperationKind.CACHE_EVICT);

            assertThat(cacheableResult).isNotNull();
            assertThat(cacheableResult.getName()).isEqualTo("cacheableOperation");
            assertThat(evictResult).isNotNull();
            assertThat(evictResult.getName()).isEqualTo("evictOperation");
        }

        @Test
        @DisplayName("same cache name and element key but different kinds are independent")
        void sameNameKeyDifferentKind_areIndependent() {
            RedisCacheableOperation cacheableOp = RedisCacheableOperation.builder()
                    .name("cacheable")
                    .cacheNames("myCache")
                    .build();

            RedisCacheEvictOperation evictOp = RedisCacheEvictOperation.builder()
                    .name("evict")
                    .cacheNames("myCache")
                    .build();

            register.register(METHOD, TARGET_CLASS, cacheableOp, OperationKind.CACHEABLE);
            register.register(METHOD, TARGET_CLASS, evictOp, OperationKind.CACHE_EVICT);

            RedisCacheableOperation cacheableResult = register.get("myCache", ELEMENT_KEY, OperationKind.CACHEABLE);
            RedisCacheEvictOperation evictResult = register.get("myCache", ELEMENT_KEY, OperationKind.CACHE_EVICT);

            assertThat(cacheableResult).isNotNull();
            assertThat(cacheableResult.getName()).isEqualTo("cacheable");
            assertThat(evictResult).isNotNull();
            assertThat(evictResult.getName()).isEqualTo("evict");
        }

        @Test
        @DisplayName("get with wrong kind on populated slot returns null")
        void get_kindMismatchOnPopulatedSlot_returnsNull() {
            RedisCacheEvictOperation evictOp = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1")
                    .build();

            register.register(METHOD, TARGET_CLASS, evictOp, OperationKind.CACHE_EVICT);

            // 槽位被 EVICT 占用,但用 CACHEABLE 查询:kind 不匹配应返回 null
            RedisCacheableOperation result = register.get("cache1", ELEMENT_KEY, OperationKind.CACHEABLE);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Type Guard Tests (ADR-0059)")
    class TypeGuardTests {

        @BeforeEach
        void setUp() {
            register = new RedisCacheRegister(100, 50);
        }

        @Test
        @DisplayName("register rejects operation whose class doesn't match kind (defensive)")
        void register_kindMismatch_skipsAndLogsError() {
            // 把 evict operation 投到 CACHEABLE 命名空间 —— kind.operationType 校验失败,
            // 防御性跳过;对应 get 也应返回 null(无写入)。
            RedisCacheEvictOperation wrongKindOp = RedisCacheEvictOperation.builder()
                    .name("wrong")
                    .cacheNames("cache1")
                    .build();

            register.register(METHOD, TARGET_CLASS, wrongKindOp, OperationKind.CACHEABLE);

            RedisCacheableOperation result = register.get("cache1", ELEMENT_KEY, OperationKind.CACHEABLE);
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

            register.register(METHOD, TARGET_CLASS, operation, OperationKind.CACHEABLE);

            RedisCacheableOperation result =
                    register.get("cache:with:colons", ELEMENT_KEY, OperationKind.CACHEABLE);
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("testOperation");
        }

        @Test
        @DisplayName("multiple registrations of different operations increments internal size")
        void multipleRegistrations_incrementsSize() {
            register = new RedisCacheRegister(100, 50);

            for (int i = 0; i < 10; i++) {
                RedisCacheableOperation operation = RedisCacheableOperation.builder()
                        .name("operation" + i)
                        .cacheNames("cache" + i)
                        .build();
                register.register(METHOD, TARGET_CLASS, operation, OperationKind.CACHEABLE);
            }

            RedisCacheableOperation result5 = register.get("cache5", ELEMENT_KEY, OperationKind.CACHEABLE);
            RedisCacheableOperation result0 = register.get("cache0", ELEMENT_KEY, OperationKind.CACHEABLE);
            assertThat(result5).isNotNull();
            assertThat(result5.getName()).isEqualTo("operation5");
            assertThat(result0).isNotNull();
            assertThat(result0.getName()).isEqualTo("operation0");
        }
    }
}
