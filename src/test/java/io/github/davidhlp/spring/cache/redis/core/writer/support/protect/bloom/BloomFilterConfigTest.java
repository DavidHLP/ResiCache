package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BloomFilterConfig 单元测试
 */
@DisplayName("BloomFilterConfig Tests")
class BloomFilterConfigTest {

    @Nested
    @DisplayName("构造函数")
    class ConstructorTests {

        @Test
        @DisplayName("使用默认值创建配置")
        void constructor_defaultValues() {
            BloomFilterConfig config = new BloomFilterConfig("bf:", 8388608, 3, 10000);

            assertThat(config.getKeyPrefix()).isEqualTo("bf:");
            assertThat(config.getBitSize()).isEqualTo(8388608);
            assertThat(config.getHashFunctions()).isEqualTo(3);
            assertThat(config.getHashCacheSize()).isEqualTo(10000);
        }

        @Test
        @DisplayName("bitSize最小值为1")
        void constructor_bitSizeMinValue() {
            // 传入0，应该被调整为1
            BloomFilterConfig config = new BloomFilterConfig("bf:", 0, 3, 10000);
            assertThat(config.getBitSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("bitSize为负数时最小值为1")
        void constructor_negativeBitSize() {
            BloomFilterConfig config = new BloomFilterConfig("bf:", -100, 3, 10000);
            assertThat(config.getBitSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("hashFunctions最小值为1")
        void constructor_hashFunctionsMinValue() {
            BloomFilterConfig config = new BloomFilterConfig("bf:", 1024, 0, 10000);
            assertThat(config.getHashFunctions()).isEqualTo(1);
        }

        @Test
        @DisplayName("hashFunctions为负数时最小值为1")
        void constructor_negativeHashFunctions() {
            BloomFilterConfig config = new BloomFilterConfig("bf:", 1024, -5, 10000);
            assertThat(config.getHashFunctions()).isEqualTo(1);
        }

        @Test
        @DisplayName("hashCacheSize最小值为1")
        void constructor_hashCacheSizeMinValue() {
            BloomFilterConfig config = new BloomFilterConfig("bf:", 1024, 3, 0);
            assertThat(config.getHashCacheSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("hashCacheSize为负数时最小值为1")
        void constructor_negativeHashCacheSize() {
            BloomFilterConfig config = new BloomFilterConfig("bf:", 1024, 3, -100);
            assertThat(config.getHashCacheSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("自定义前缀")
        void constructor_customPrefix() {
            BloomFilterConfig config = new BloomFilterConfig("custom:", 1024, 3, 1000);
            assertThat(config.getKeyPrefix()).isEqualTo("custom:");
        }
    }

    @Nested
    @DisplayName("getter方法")
    class GetterTests {

        @Test
        @DisplayName("getKeyPrefix返回正确值")
        void getKeyPrefix() {
            BloomFilterConfig config = new BloomFilterConfig("test:", 1024, 3, 1000);
            assertThat(config.getKeyPrefix()).isEqualTo("test:");
        }

        @Test
        @DisplayName("getBitSize返回正确值")
        void getBitSize() {
            BloomFilterConfig config = new BloomFilterConfig("bf:", 4096, 3, 1000);
            assertThat(config.getBitSize()).isEqualTo(4096);
        }

        @Test
        @DisplayName("getHashFunctions返回正确值")
        void getHashFunctions() {
            BloomFilterConfig config = new BloomFilterConfig("bf:", 1024, 5, 1000);
            assertThat(config.getHashFunctions()).isEqualTo(5);
        }

        @Test
        @DisplayName("getHashCacheSize返回正确值")
        void getHashCacheSize() {
            BloomFilterConfig config = new BloomFilterConfig("bf:", 1024, 3, 5000);
            assertThat(config.getHashCacheSize()).isEqualTo(5000);
        }
    }
}
