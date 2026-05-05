package io.github.davidhlp.spring.cache.redis.register.operation;

import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisCacheableOperation 单元测试
 */
@DisplayName("RedisCacheableOperation Tests")
class RedisCacheableOperationTest {

    @Nested
    @DisplayName("Builder 测试")
    class BuilderTests {

        @Test
        @DisplayName("使用 builder 创建基本操作")
        void builder_basicOperation() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("testOperation")
                    .cacheNames("test-cache")
                    .key("#id")
                    .build();

            assertThat(operation.getName()).isEqualTo("testOperation");
            assertThat(operation.getCacheNames()).contains("test-cache");
            assertThat(operation.getKey()).isEqualTo("#id");
        }

        @Test
        @DisplayName("builder 默认值")
        void builder_defaultValues() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("test")
                    .build();

            assertThat(operation.isSync()).isFalse();
            assertThat(operation.getSyncTimeout()).isEqualTo(10L);
            assertThat(operation.getTtl()).isEqualTo(0L);
            assertThat(operation.isCacheNullValues()).isFalse();
            assertThat(operation.isUseBloomFilter()).isFalse();
            assertThat(operation.isRandomTtl()).isFalse();
            assertThat(operation.isEnablePreRefresh()).isFalse();
        }

        @Test
        @DisplayName("builder 设置所有属性")
        void builder_allProperties() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("fullOperation")
                    .cacheNames("cache1", "cache2")
                    .key("#key")
                    .unless("#result == null")
                    .sync(true)
                    .syncTimeout(20)
                    .ttl(300)
                    .type(String.class)
                    .cacheNullValues(true)
                    .useBloomFilter(true)
                    .randomTtl(true)
                    .variance(0.2f)
                    .enablePreRefresh(true)
                    .preRefreshThreshold(0.8)
                    .preRefreshMode(PreRefreshMode.ASYNC)
                    .build();

            assertThat(operation.getName()).isEqualTo("fullOperation");
            assertThat(operation.getCacheNames()).containsExactly("cache1", "cache2");
            assertThat(operation.getKey()).isEqualTo("#key");
            assertThat(operation.getUnless()).isEqualTo("#result == null");
            assertThat(operation.isSync()).isTrue();
            assertThat(operation.getSyncTimeout()).isEqualTo(20L);
            assertThat(operation.getTtl()).isEqualTo(300L);
            assertThat(operation.getType()).isEqualTo(String.class);
            assertThat(operation.isCacheNullValues()).isTrue();
            assertThat(operation.isUseBloomFilter()).isTrue();
            assertThat(operation.isRandomTtl()).isTrue();
            assertThat(operation.getVariance()).isEqualTo(0.2f);
            assertThat(operation.isEnablePreRefresh()).isTrue();
            assertThat(operation.getPreRefreshThreshold()).isEqualTo(0.8);
            assertThat(operation.getPreRefreshMode()).isEqualTo(PreRefreshMode.ASYNC);
        }

        @Test
        @DisplayName("链式调用")
        void builder_chaining() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("chained")
                    .cacheNames("cache")
                    .key("#key")
                    .ttl(100)
                    .sync(true)
                    .build();

            assertThat(operation.getTtl()).isEqualTo(100L);
            assertThat(operation.isSync()).isTrue();
        }
    }

    @Nested
    @DisplayName("getter 方法")
    class GetterTests {

        @Test
        @DisplayName("getUnless")
        void getUnless() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("test")
                    .unless("#result eq null")
                    .build();

            assertThat(operation.getUnless()).isEqualTo("#result eq null");
        }

        @Test
        @DisplayName("getSync")
        void getSync() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("test")
                    .sync(true)
                    .build();

            assertThat(operation.isSync()).isTrue();
        }

        @Test
        @DisplayName("getSyncTimeout")
        void getSyncTimeout() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("test")
                    .syncTimeout(50)
                    .build();

            assertThat(operation.getSyncTimeout()).isEqualTo(50L);
        }

        @Test
        @DisplayName("getTtl")
        void getTtl() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("test")
                    .ttl(600)
                    .build();

            assertThat(operation.getTtl()).isEqualTo(600L);
        }

        @Test
        @DisplayName("getType")
        void getType() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("test")
                    .type(Integer.class)
                    .build();

            assertThat(operation.getType()).isEqualTo(Integer.class);
        }

        @Test
        @DisplayName("isCacheNullValues")
        void isCacheNullValues() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("test")
                    .cacheNullValues(true)
                    .build();

            assertThat(operation.isCacheNullValues()).isTrue();
        }

        @Test
        @DisplayName("isUseBloomFilter")
        void isUseBloomFilter() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("test")
                    .useBloomFilter(true)
                    .build();

            assertThat(operation.isUseBloomFilter()).isTrue();
        }

        @Test
        @DisplayName("isRandomTtl")
        void isRandomTtl() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("test")
                    .randomTtl(true)
                    .build();

            assertThat(operation.isRandomTtl()).isTrue();
        }

        @Test
        @DisplayName("getVariance")
        void getVariance() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("test")
                    .variance(0.3f)
                    .build();

            assertThat(operation.getVariance()).isEqualTo(0.3f);
        }

        @Test
        @DisplayName("isEnablePreRefresh")
        void isEnablePreRefresh() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("test")
                    .enablePreRefresh(true)
                    .build();

            assertThat(operation.isEnablePreRefresh()).isTrue();
        }

        @Test
        @DisplayName("getPreRefreshThreshold")
        void getPreRefreshThreshold() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("test")
                    .preRefreshThreshold(0.9)
                    .build();

            assertThat(operation.getPreRefreshThreshold()).isEqualTo(0.9);
        }

        @Test
        @DisplayName("getPreRefreshMode")
        void getPreRefreshMode() {
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("test")
                    .preRefreshMode(PreRefreshMode.SYNC)
                    .build();

            assertThat(operation.getPreRefreshMode()).isEqualTo(PreRefreshMode.SYNC);
        }
    }
}
