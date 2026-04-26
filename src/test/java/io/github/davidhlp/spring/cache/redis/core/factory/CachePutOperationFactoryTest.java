package io.github.davidhlp.spring.cache.redis.core.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshMode;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCachePutOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CachePutOperationFactory 单元测试
 */
@DisplayName("CachePutOperationFactory Tests")
class CachePutOperationFactoryTest {

    private final CachePutOperationFactory factory = new CachePutOperationFactory();

    private RedisCachePut createAnnotation(
            String[] cacheNames,
            String[] values,
            String key,
            long ttl,
            boolean enablePreRefresh,
            double preRefreshThreshold,
            PreRefreshMode preRefreshMode,
            String condition,
            String unless,
            boolean useBloomFilter,
            long expectedInsertions) {
        return new TestRedisCachePut(cacheNames, values, key, ttl, enablePreRefresh,
                preRefreshThreshold, preRefreshMode, condition, unless, useBloomFilter, expectedInsertions);
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
                    PreRefreshMode.ASYNC,
                    "#args[0] != null",
                    "#result != null",
                    true,
                    500000L
            );
            Method method = getTestMethod();
            TestClass target = new TestClass();
            String[] args = {"arg1"};

            RedisCachePutOperation operation = factory.create(method, annotation, target, args, "generated-key");

            assertThat(operation.getName()).isEqualTo("testMethod");
            assertThat(operation.getKey()).isEqualTo("generated-key");
            assertThat(operation.getTtl()).isEqualTo(120L);
            assertThat(operation.getType()).isEqualTo(String.class);
            assertThat(operation.isEnablePreRefresh()).isTrue();
            assertThat(operation.getPreRefreshThreshold()).isEqualTo(0.5);
            assertThat(operation.getPreRefreshMode()).isEqualTo(PreRefreshMode.ASYNC);
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
                    PreRefreshMode.SYNC,
                    "",
                    "",
                    false,
                    100000L
            );
            Method method = getTestMethod();

            RedisCachePutOperation operation = factory.create(method, annotation, new TestClass(), new Object[]{}, "key");

            assertThat(operation.isUseBloomFilter()).isFalse();
            assertThat(operation.getExpectedInsertions()).isEqualTo(100000L);
        }
    }

    @Nested
    @DisplayName("supports tests")
    class SupportsTests {

        @Test
        @DisplayName("returns true for RedisCachePut annotation")
        void supports_redisCachePut_returnsTrue() throws NoSuchMethodException {
            RedisCachePut annotation = createAnnotation(
                    new String[]{},
                    new String[]{},
                    "",
                    60L,
                    false,
                    0.3,
                    PreRefreshMode.SYNC,
                    "",
                    "",
                    false,
                    100000L
            );

            assertThat(factory.supports(annotation)).isTrue();
        }

        @Test
        @DisplayName("returns false for other annotations")
        void supports_otherAnnotation_returnsFalse() {
            Annotation otherAnnotation = new SomeOtherAnnotation();

            assertThat(factory.supports(otherAnnotation)).isFalse();
        }
    }

    // Test helper class
    static class TestClass {
        public void testMethod(String arg) {}
    }

    // Test implementation of RedisCachePut
    static class TestRedisCachePut implements RedisCachePut {
        private final String[] cacheNames;
        private final String[] values;
        private final String key;
        private final long ttl;
        private final boolean enablePreRefresh;
        private final double preRefreshThreshold;
        private final PreRefreshMode preRefreshMode;
        private final String condition;
        private final String unless;
        private final boolean useBloomFilter;
        private final long expectedInsertions;

        TestRedisCachePut(String[] cacheNames, String[] values, String key, long ttl,
                          boolean enablePreRefresh, double preRefreshThreshold, PreRefreshMode preRefreshMode,
                          String condition, String unless, boolean useBloomFilter, long expectedInsertions) {
            this.cacheNames = cacheNames;
            this.values = values;
            this.key = key;
            this.ttl = ttl;
            this.enablePreRefresh = enablePreRefresh;
            this.preRefreshThreshold = preRefreshThreshold;
            this.preRefreshMode = preRefreshMode;
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
        public boolean enablePreRefresh() {
            return enablePreRefresh;
        }

        @Override
        public double preRefreshThreshold() {
            return preRefreshThreshold;
        }

        @Override
        public PreRefreshMode preRefreshMode() {
            return preRefreshMode;
        }
    }

    // Other annotation for negative testing
    static class SomeOtherAnnotation implements Annotation {
        @Override
        public Class<? extends Annotation> annotationType() {
            return SomeOtherAnnotation.class;
        }
    }
}
