package io.github.davidhlp.spring.cache.redis.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheResult 单元测试 — ADR-0039 收敛后形态(2 字段 + 4 工厂)。
 *
 * <p>删除的字段({@code hit / rejectedByBloomFilter / exception})与读法
 * ({@code isHit / isRejectedByBloomFilter / isFailure / hasResult / getException})
 * 已在 ADR-0039 删除,本测试仅覆盖保留契约:{@code success / resultBytes} 字段、
 * {@code isSuccess / getResultBytes} 读法,以及 {@code miss()} 作为 {@code success()}
 * 语义别名的等价性。
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

            // ADR-0039:miss() 字节同构 success()(都 success=true、resultBytes=null),
            // 保留为独立工厂仅为调用方表达 GET 未命中语义
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getResultBytes()).isNull();
        }

        @Test
        @DisplayName("failure() - 创建失败结果")
        void failure_createsFailureResult() {
            CacheResult result = CacheResult.failure();

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getResultBytes()).isNull();
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
