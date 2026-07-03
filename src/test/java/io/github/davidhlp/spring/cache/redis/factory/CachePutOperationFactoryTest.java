package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
import io.github.davidhlp.spring.cache.redis.operation.RedisCachePutOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CachePutOperationFactory 单元测试
 *
 * <p>ADR-0028:删除 supports() 测试块;create 调用跟随接口签名窄化为 3 参。
 */
@DisplayName("CachePutOperationFactory Tests")
class CachePutOperationFactoryTest {

    private final RedisCacheAttributesProjector projector = new RedisCacheAttributesProjector();
    private final CachePutOperationFactory factory = new CachePutOperationFactory(projector);

    private RedisCachePut createAnnotation(
            String[] cacheNames,
            String[] values,
            String key,
            long ttl,
            boolean enableEarlyExpiration,
            double earlyExpirationThreshold,
            EarlyExpirationMode earlyExpirationMode,
            String condition,
            String unless,
            boolean useBloomFilter,
            long expectedInsertions) {
        return new TestRedisCachePut(cacheNames, values, key, ttl, enableEarlyExpiration,
                earlyExpirationThreshold, earlyExpirationMode, condition, unless, useBloomFilter, expectedInsertions);
    }

    private Method getTestMethod() throws NoSuchMethodException {
        return TestClass.class.getMethod("testMethod", String.class);
    }

    @Nested
    @DisplayName("create tests")
    class CreateTests {

        @Test
        @DisplayName("creates operation with all properties")
        void create_withAllProperties_createsCorrectly() throws NoSuchMethodException {
            RedisCachePut annotation = createAnnotation(
                    new String[]{"cache1", "cache2"},
                    new String[]{},
                    "key",
                    120L,
                    true,
                    0.5,
                    EarlyExpirationMode.ASYNC,
                    "#args[0] != null",
                    "#result != null",
                    true,
                    500000L
            );
            Method method = getTestMethod();

            RedisCachePutOperation operation = factory.create(method, annotation, "generated-key");

            assertThat(operation.getName()).isEqualTo("testMethod");
            assertThat(operation.getKey()).isEqualTo("generated-key");
            assertThat(operation.getTtl()).isEqualTo(120L);
            assertThat(operation.getType()).isEqualTo(String.class);
            assertThat(operation.isEnableEarlyExpiration()).isTrue();
            assertThat(operation.getEarlyExpirationThreshold()).isEqualTo(0.5);
            assertThat(operation.getEarlyExpirationMode()).isEqualTo(EarlyExpirationMode.ASYNC);
            assertThat(operation.getCondition()).isEqualTo("#args[0] != null");
            assertThat(operation.getUnless()).isEqualTo("#result != null");
            assertThat(operation.isUseBloomFilter()).isTrue();
            assertThat(operation.getExpectedInsertions()).isEqualTo(500000L);
            assertThat(operation.getCacheNames()).containsExactly("cache1", "cache2");
        }

        @Test
        @DisplayName("creates operation with default bloom filter settings")
        void create_withDefaultBloomSettings_createsCorrectly() throws NoSuchMethodException {
            RedisCachePut annotation = createAnnotation(
                    new String[]{"cache1"},
                    new String[]{},
                    "key",
                    60L,
                    false,
                    0.3,
                    EarlyExpirationMode.SYNC,
                    "",
                    "",
                    false,
                    100000L
            );
            Method method = getTestMethod();

            RedisCachePutOperation operation = factory.create(method, annotation, "key");

            assertThat(operation.isUseBloomFilter()).isFalse();
            assertThat(operation.getExpectedInsertions()).isEqualTo(100000L);
        }
    }

    // Test helper class
    static class TestClass {
        public void testMethod(String arg) { }
    }

    // Test implementation of RedisCachePut
    static class TestRedisCachePut implements RedisCachePut {
        private final String[] cacheNames;
        private final String[] values;
        private final String key;
        private final long ttl;
        private final boolean enableEarlyExpiration;
        private final double earlyExpirationThreshold;
        private final EarlyExpirationMode earlyExpirationMode;
        private final String condition;
        private final String unless;
        private final boolean useBloomFilter;
        private final long expectedInsertions;

        TestRedisCachePut(String[] cacheNames, String[] values, String key, long ttl,
                          boolean enableEarlyExpiration, double earlyExpirationThreshold, EarlyExpirationMode earlyExpirationMode,
                          String condition, String unless, boolean useBloomFilter, long expectedInsertions) {
            this.cacheNames = cacheNames;
            this.values = values;
            this.key = key;
            this.ttl = ttl;
            this.enableEarlyExpiration = enableEarlyExpiration;
            this.earlyExpirationThreshold = earlyExpirationThreshold;
            this.earlyExpirationMode = earlyExpirationMode;
            this.condition = condition;
            this.unless = unless;
            this.useBloomFilter = useBloomFilter;
            this.expectedInsertions = expectedInsertions;
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return RedisCachePut.class;
        }

        @Override
        public String[] value() {
            return values;
        }

        @Override
        public String[] cacheNames() {
            return cacheNames;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String keyGenerator() {
            return "";
        }

        @Override
        public String cacheManager() {
            return "";
        }

        @Override
        public String cacheResolver() {
            return "";
        }

        @Override
        public String condition() {
            return condition;
        }

        @Override
        public String unless() {
            return unless;
        }

        @Override
        public boolean sync() {
            return false;
        }

        @Override
        public long syncTimeout() {
            return 10;
        }

        @Override
        public long ttl() {
            return ttl;
        }

        @Override
        public Class<?> type() {
            return String.class;
        }

        @Override
        public boolean cacheNullValues() {
            return false;
        }

        @Override
        public boolean useBloomFilter() {
            return useBloomFilter;
        }

        @Override
        public long expectedInsertions() {
            return expectedInsertions;
        }

        @Override
        public double falseProbability() {
            return 0.01;
        }

        @Override
        public boolean randomTtl() {
            return false;
        }

        @Override
        public float variance() {
            return 0.2f;
        }

        @Override
        public boolean enableEarlyExpiration() {
            return enableEarlyExpiration;
        }

        @Override
        public double earlyExpirationThreshold() {
            return earlyExpirationThreshold;
        }

        @Override
        public EarlyExpirationMode earlyExpirationMode() {
            return earlyExpirationMode;
        }
    }
}
