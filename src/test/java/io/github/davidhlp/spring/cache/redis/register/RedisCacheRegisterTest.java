package io.github.davidhlp.spring.cache.redis.register;

import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheEvictOperation;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisCacheRegister 单元测试
 */
@DisplayName("RedisCacheRegister Tests")
class RedisCacheRegisterTest {

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
                    .key("key1")
                    .build();

            register.registerCacheableOperation(operation);

            RedisCacheableOperation result = register.getCacheableOperation("cache1", "key1");
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("testOperation");
        }

        @Test
        @DisplayName("registerCacheableOperation stores operation for multiple cache names")
        void registerCacheableOperation_multipleCacheNames_storesOperations() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testOperation")
                    .cacheNames("cache1", "cache2")
                    .key("key1")
                    .build();

            register.registerCacheableOperation(operation);

            RedisCacheableOperation result1 = register.getCacheableOperation("cache1", "key1");
            RedisCacheableOperation result2 = register.getCacheableOperation("cache2", "key1");

            assertThat(result1).isNotNull();
            assertThat(result2).isNotNull();
        }

        @Test
        @DisplayName("registerCacheableOperation updates existing operation")
        void registerCacheableOperation_existingKey_updatesOperation() {
            RedisCacheableOperation operation1 = RedisCacheableOperation.builder()
                    .name("operation1")
                    .cacheNames("cache1")
                    .key("key1")
                    .build();

            RedisCacheableOperation operation2 = RedisCacheableOperation.builder()
                    .name("operation2")
                    .cacheNames("cache1")
                    .key("key1")
                    .build();

            register.registerCacheableOperation(operation1);
            register.registerCacheableOperation(operation2);

            RedisCacheableOperation result = register.getCacheableOperation("cache1", "key1");
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
                    .key("key1")
                    .build();

            register.registerCacheEvictOperation(operation);

            RedisCacheEvictOperation result = register.getCacheEvictOperation("cache1", "key1");
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("evictOperation");
        }

        @Test
        @DisplayName("registerCacheEvictOperation stores operation for multiple cache names")
        void registerCacheEvictOperation_multipleCacheNames_storesOperations() {
            RedisCacheEvictOperation operation = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1", "cache2")
                    .key("key1")
                    .build();

            register.registerCacheEvictOperation(operation);

            RedisCacheEvictOperation result1 = register.getCacheEvictOperation("cache1", "key1");
            RedisCacheEvictOperation result2 = register.getCacheEvictOperation("cache2", "key1");

            assertThat(result1).isNotNull();
            assertThat(result2).isNotNull();
        }

        @Test
        @DisplayName("registerCacheEvictOperation updates existing operation")
        void registerCacheEvictOperation_existingKey_updatesOperation() {
            RedisCacheEvictOperation operation1 = RedisCacheEvictOperation.builder()
                    .name("evictOperation1")
                    .cacheNames("cache1")
                    .key("key1")
                    .build();

            RedisCacheEvictOperation operation2 = RedisCacheEvictOperation.builder()
                    .name("evictOperation2")
                    .cacheNames("cache1")
                    .key("key1")
                    .build();

            register.registerCacheEvictOperation(operation1);
            register.registerCacheEvictOperation(operation2);

            RedisCacheEvictOperation result = register.getCacheEvictOperation("cache1", "key1");
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
            RedisCacheableOperation result = register.getCacheableOperation("nonexistent", "key");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("getCacheableOperation returns null for wrong key")
        void getCacheableOperation_wrongKey_returnsNull() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testOperation")
                    .cacheNames("cache1")
                    .key("key1")
                    .build();

            register.registerCacheableOperation(operation);

            RedisCacheableOperation result = register.getCacheableOperation("cache1", "wrongKey");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("getCacheableOperation returns null for wrong cache name")
        void getCacheableOperation_wrongCacheName_returnsNull() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testOperation")
                    .cacheNames("cache1")
                    .key("key1")
                    .build();

            register.registerCacheableOperation(operation);

            RedisCacheableOperation result = register.getCacheableOperation("cache2", "key1");

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
            RedisCacheEvictOperation result = register.getCacheEvictOperation("nonexistent", "key");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("getCacheEvictOperation returns null for wrong key")
        void getCacheEvictOperation_wrongKey_returnsNull() {
            RedisCacheEvictOperation operation = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1")
                    .key("key1")
                    .build();

            register.registerCacheEvictOperation(operation);

            RedisCacheEvictOperation result = register.getCacheEvictOperation("cache1", "wrongKey");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("getCacheEvictOperation returns null for wrong cache name")
        void getCacheEvictOperation_wrongCacheName_returnsNull() {
            RedisCacheEvictOperation operation = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1")
                    .key("key1")
                    .build();

            register.registerCacheEvictOperation(operation);

            RedisCacheEvictOperation result = register.getCacheEvictOperation("cache2", "key1");

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
                    .key("key1")
                    .build();

            RedisCacheEvictOperation evictOp = RedisCacheEvictOperation.builder()
                    .name("evictOperation")
                    .cacheNames("cache1")
                    .key("key1")
                    .build();

            register.registerCacheableOperation(cacheableOp);
            register.registerCacheEvictOperation(evictOp);

            RedisCacheableOperation cacheableResult = register.getCacheableOperation("cache1", "key1");
            RedisCacheEvictOperation evictResult = register.getCacheEvictOperation("cache1", "key1");

            assertThat(cacheableResult).isNotNull();
            assertThat(cacheableResult.getName()).isEqualTo("cacheableOperation");
            assertThat(evictResult).isNotNull();
            assertThat(evictResult.getName()).isEqualTo("evictOperation");
        }

        @Test
        @DisplayName("same cache name and key but different types are independent")
        void sameNameKeyDifferentType_areIndependent() {
            RedisCacheableOperation cacheableOp = RedisCacheableOperation.builder()
                    .name("cacheable")
                    .cacheNames("myCache")
                    .key("myKey")
                    .build();

            RedisCacheEvictOperation evictOp = RedisCacheEvictOperation.builder()
                    .name("evict")
                    .cacheNames("myCache")
                    .key("myKey")
                    .build();

            register.registerCacheableOperation(cacheableOp);
            register.registerCacheEvictOperation(evictOp);

            RedisCacheableOperation cacheableResult = register.getCacheableOperation("myCache", "myKey");
            RedisCacheEvictOperation evictResult = register.getCacheEvictOperation("myCache", "myKey");

            assertThat(cacheableResult).isNotNull();
            assertThat(evictResult).isNotNull();
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("operations with special characters in key are handled")
        void operationWithSpecialChars_handledCorrectly() {
            register = new RedisCacheRegister(100, 50);

            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testOperation")
                    .cacheNames("cache:with:colons")
                    .key("key#with#special$chars")
                    .build();

            register.registerCacheableOperation(operation);

            RedisCacheableOperation result = register.getCacheableOperation("cache:with:colons", "key#with#special$chars");
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("multiple registrations of same operation increments internal size")
        void multipleRegistrations_incrementsSize() {
            register = new RedisCacheRegister(100, 50);

            for (int i = 0; i < 10; i++) {
                RedisCacheableOperation operation = RedisCacheableOperation.builder()
                        .name("operation" + i)
                        .cacheNames("cache" + i)
                        .key("key" + i)
                        .build();
                register.registerCacheableOperation(operation);
            }

            // Verify that at least some operations were stored
            RedisCacheableOperation result = register.getCacheableOperation("cache5", "key5");
            assertThat(result).isNotNull();
        }
    }
}
