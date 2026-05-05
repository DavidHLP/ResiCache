package io.github.davidhlp.spring.cache.redis.core.writer.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheResult 单元测试
 *
 * <p>测试 CacheResult 的各种工厂方法、构建器和便捷方法
 */
@DisplayName("CacheResult Tests")
class CacheResultTest {

    @Nested
    @DisplayName("静态工厂方法")
    class FactoryMethodTests {

        @Test
        @DisplayName("success() - 创建无返回值的成功结果")
        void success_noValue_createsSuccessResult() {
            CacheResult result = CacheResult.success();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isHit()).isFalse();
            assertThat(result.isFailure()).isFalse();
            assertThat(result.hasResult()).isFalse();
            assertThat(result.getResultBytes()).isNull();
            assertThat(result.getException()).isNull();
            assertThat(result.isRejectedByBloomFilter()).isFalse();
        }

        @Test
        @DisplayName("success(byte[]) - 创建带返回值的成功结果")
        void success_withValue_createsSuccessResultWithValue() {
            byte[] value = "test-value".getBytes();
            CacheResult result = CacheResult.success(value);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isHit()).isTrue();
            assertThat(result.hasResult()).isTrue();
            assertThat(result.getResultBytes()).isEqualTo(value);
            assertThat(result.isFailure()).isFalse();
        }

        @Test
        @DisplayName("miss() - 创建缓存未命中结果")
        void miss_createsMissResult() {
            CacheResult result = CacheResult.miss();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isHit()).isFalse();
            assertThat(result.hasResult()).isFalse();
            assertThat(result.getResultBytes()).isNull();
        }

        @Test
        @DisplayName("rejectedByBloomFilter() - 创建被布隆过滤器拒绝的结果")
        void rejectedByBloomFilter_createsRejectedResult() {
            CacheResult result = CacheResult.rejectedByBloomFilter();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isHit()).isFalse();
            assertThat(result.isRejectedByBloomFilter()).isTrue();
        }

        @Test
        @DisplayName("failure(Exception) - 创建失败结果")
        void failure_withException_createsFailureResult() {
            RuntimeException exception = new RuntimeException("Test error");
            CacheResult result = CacheResult.failure(exception);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isFailure()).isTrue();
            assertThat(result.getException()).isEqualTo(exception);
        }
    }

    @Nested
    @DisplayName("Builder 模式")
    class BuilderTests {

        @Test
        @DisplayName("使用 builder 构建完整结果")
        void builder_fullResult_buildsCorrectly() {
            byte[] value = "value".getBytes();
            RuntimeException exception = new RuntimeException("error");

            CacheResult result = CacheResult.builder()
                    .success(true)
                    .resultBytes(value)
                    .hit(true)
                    .rejectedByBloomFilter(true)
                    .exception(exception)
                    .build();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getResultBytes()).isEqualTo(value);
            assertThat(result.isHit()).isTrue();
            assertThat(result.isRejectedByBloomFilter()).isTrue();
            assertThat(result.getException()).isEqualTo(exception);
        }

        @Test
        @DisplayName("使用 builder 构建最小结果")
        void builder_minimal_buildsCorrectly() {
            CacheResult result = CacheResult.builder().build();

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getResultBytes()).isNull();
            assertThat(result.isHit()).isFalse();
        }

        @Test
        @DisplayName("Builder 默认值测试")
        void builder_defaultValues_areCorrect() {
            CacheResult result = CacheResult.builder()
                    .success(true)
                    .build();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isHit()).isFalse();
            assertThat(result.isRejectedByBloomFilter()).isFalse();
            assertThat(result.hasResult()).isFalse();
        }
    }

    @Nested
    @DisplayName("便捷方法")
    class ConvenienceMethodTests {

        @Test
        @DisplayName("hasResult - 当 resultBytes 不为 null 时返回 true")
        void hasResult_withResultBytes_returnsTrue() {
            CacheResult result = CacheResult.success("test".getBytes());
            assertThat(result.hasResult()).isTrue();
        }

        @Test
        @DisplayName("hasResult - 当 resultBytes 为 null 时返回 false")
        void hasResult_withNullResultBytes_returnsFalse() {
            CacheResult result = CacheResult.success();
            assertThat(result.hasResult()).isFalse();
        }

        @Test
        @DisplayName("isFailure - 当 success 为 false 时返回 true")
        void isFailure_whenNotSuccess_returnsTrue() {
            CacheResult result = CacheResult.failure(new RuntimeException());
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        @DisplayName("isFailure - 当 success 为 true 时返回 false")
        void isFailure_whenSuccess_returnsFalse() {
            CacheResult result = CacheResult.success();
            assertThat(result.isFailure()).isFalse();
        }
    }

    @Nested
    @DisplayName("边界情况")
    class EdgeCaseTests {

        @Test
        @DisplayName("success() 之后设置 hit 为 true")
        void success_thenHitBecomesTrue() {
            CacheResult result = CacheResult.success();
            // success() 默认 hit 为 false
            assertThat(result.isHit()).isFalse();
        }

        @Test
        @DisplayName("failure() 创建的 result 的 hasResult 返回 false")
        void failure_resultHasNoResult() {
            CacheResult result = CacheResult.failure(new RuntimeException("error"));
            assertThat(result.hasResult()).isFalse();
        }

        @Test
        @DisplayName("miss() 结果的 isFailure 返回 false")
        void miss_resultIsNotFailure() {
            CacheResult result = CacheResult.miss();
            assertThat(result.isFailure()).isFalse();
        }

        @Test
        @DisplayName("rejectedByBloomFilter() 结果的 isFailure 返回 false")
        void rejectedByBloomFilter_resultIsNotFailure() {
            CacheResult result = CacheResult.rejectedByBloomFilter();
            assertThat(result.isFailure()).isFalse();
        }
    }
}
