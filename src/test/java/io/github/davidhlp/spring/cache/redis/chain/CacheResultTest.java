package io.github.davidhlp.spring.cache.redis.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheResult 单元测试 — 结果状态、诊断元数据和 PIFA 三态。
 *
 * <p>本测试覆盖 success / miss / inserted / existing / failure 工厂、
 * cause/failure kind/operation 保留，以及 builder 的默认行为。
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
            assertThat(result.getResultBytes()).isNull();
        }

        @Test
        @DisplayName("success(byte[]) - 创建带返回值的成功结果")
        void success_withValue_createsSuccessResultWithValue() {
            byte[] value = "test-value".getBytes();
            CacheResult result = CacheResult.success(value);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getResultBytes()).isEqualTo(value);
        }

        @Test
        @DisplayName("miss() - success 的语义别名(未命中,字节同构 success())")
        void miss_isSuccessAlias() {
            CacheResult result = CacheResult.miss();

            // miss() 字节同构 success()(都 success=true、resultBytes=null),
            // 保留为独立工厂仅为调用方表达 GET 未命中语义
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getResultBytes()).isNull();
        }

        @Test
        @DisplayName("failure() - 创建失败结果")
        void failure_createsFailureResult() {
            CacheResult result = CacheResult.failure("PUT", "REDIS", new IllegalStateException("down"));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getResultBytes()).isNull();
            assertThat(result.getOperation()).isEqualTo("PUT");
            assertThat(result.getFailureKind()).isEqualTo("REDIS");
            assertThat(result.getCause()).isInstanceOf(IllegalStateException.class);
            assertThat(result.getOutcome()).isEqualTo("FAILURE");
        }

        @Test
        @DisplayName("inserted and existing outcomes remain distinct")
        void putIfAbsentOutcomes_areDistinct() {
            assertThat(CacheResult.inserted().getOutcome()).isEqualTo("INSERTED");
            assertThat(CacheResult.existing(null).getOutcome()).isEqualTo("EXISTING");
        }

    }

    @Nested
    @DisplayName("Builder 模式")
    class BuilderTests {

        @Test
        @DisplayName("使用 builder 构建完整结果")
        void builder_fullResult_buildsCorrectly() {
            byte[] value = "value".getBytes();

            CacheResult result = CacheResult.builder()
                    .success(true)
                    .resultBytes(value)
                    .build();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getResultBytes()).isEqualTo(value);
        }

        @Test
        @DisplayName("使用 builder 构建最小结果(默认 success=false)")
        void builder_minimal_buildsCorrectly() {
            CacheResult result = CacheResult.builder().build();

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getResultBytes()).isNull();
        }
    }
}
