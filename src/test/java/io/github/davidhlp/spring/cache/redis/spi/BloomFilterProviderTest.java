package io.github.davidhlp.spring.cache.redis.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BloomFilterProvider 单元测试
 */
@DisplayName("BloomFilterProvider Tests")
class BloomFilterProviderTest {

    @Nested
    @DisplayName("接口契约")
    class ContractTests {

        @Test
        @DisplayName("实现类需要实现create方法")
        void implementerHasCreateMethod() {
            // 验证接口存在
            BloomFilterProvider provider = new TestBloomFilterProvider();
            assertThat(provider).isNotNull();
        }

        @Test
        @DisplayName("实现类需要实现getName方法")
        void implementerHasGetNameMethod() {
            TestBloomFilterProvider provider = new TestBloomFilterProvider();
            assertThat(provider.getName()).isEqualTo("test");
        }

        @Test
        @DisplayName("create方法返回BloomFilter实例")
        void createReturnsBloomFilter() {
            TestBloomFilterProvider provider = new TestBloomFilterProvider();
            BloomFilter filter = provider.create("cache", 100000, 0.01);
            assertThat(filter).isNotNull();
        }
    }

    // Test implementation
    static class TestBloomFilterProvider implements BloomFilterProvider {
        @Override
        public BloomFilter create(String cacheName, long expectedInsertions, double falseProbability) {
            return new BloomFilter() {
                @Override
                public boolean mightContain(String key) {
                    return true;
                }

                @Override
                public void add(String key) {
                }

                @Override
                public void clear() {
                }
            };
        }

        @Override
        public String getName() {
            return "test";
        }
    }
}
