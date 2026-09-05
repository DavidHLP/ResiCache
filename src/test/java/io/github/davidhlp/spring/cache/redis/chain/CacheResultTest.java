package io.github.davidhlp.spring.cache.redis.chain;




import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheResult 单元测试 — 合法状态模型(ADR-03)。
 *
 * <p>锁定契约:
 * <ul>
 *   <li>immutable final 值类型:无 setter/builder;状态只能经受控工厂构造</li>
 *   <li>{@code isSuccess} 由 {@link CacheResult.Outcome} 派生(success/miss/inserted/
 *       existing=true,failure=false),非存储字段 — 无矛盾组合</li>
 *   <li>failure 必须携带 typed operation/kind/cause</li>
 *   <li>byte[] 防御性复制(构造与读取双向)</li>
 * </ul>
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
            assertThat(result.outcome()).isEqualTo(CacheResult.Outcome.SUCCESS);
            assertThat(result.resultBytes()).isNull();
            assertThat(result.operation()).isNull();
            assertThat(result.failureKind()).isNull();
            assertThat(result.cause()).isNull();
        }

        @Test
        @DisplayName("success(byte[]) - 创建带返回值的成功结果")
        void success_withValue_createsSuccessResultWithValue() {
            byte[] value = "test-value".getBytes();
            CacheResult result = CacheResult.success(value);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.resultBytes()).isEqualTo(value);
        }

        @Test
        @DisplayName("miss() - 未命中(独立 outcome,isSuccess=true)")
        void miss_isSuccessAlias() {
            CacheResult result = CacheResult.miss();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.outcome()).isEqualTo(CacheResult.Outcome.MISS);
            assertThat(result.resultBytes()).isNull();
        }

        @Test
        @DisplayName("failure() - typed operation/kind/cause,isSuccess=false")
        void failure_createsFailureResult() {
            CacheResult result = CacheResult.failure(
                    CacheOperation.PUT,
                    CacheResult.FailureKind.REDIS,
                    new IllegalStateException("down"));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.outcome()).isEqualTo(CacheResult.Outcome.FAILURE);
            assertThat(result.operation()).isEqualTo(CacheOperation.PUT);
            assertThat(result.failureKind()).isEqualTo(CacheResult.FailureKind.REDIS);
            assertThat(result.cause()).isInstanceOf(IllegalStateException.class);
            assertThat(result.resultBytes()).isNull();
        }

        @Test
        @DisplayName("failure(null operation) 立即 NullPointerException(RM-006 不变量)")
        void failure_nullOperation_rejected() {
            org.assertj.core.api.Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> CacheResult.failure(
                            null, CacheResult.FailureKind.REDIS, new IllegalStateException("down")))
                    .withMessage("operation");
        }

        @Test
        @DisplayName("failure(null kind) 立即 NullPointerException(RM-006 不变量)")
        void failure_nullKind_rejected() {
            org.assertj.core.api.Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> CacheResult.failure(
                            CacheOperation.PUT, null, new IllegalStateException("down")))
                    .withMessage("kind");
        }

        @Test
        @DisplayName("failure(null cause) 立即 NullPointerException(RM-006 不变量)")
        void failure_nullCause_rejected() {
            org.assertj.core.api.Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> CacheResult.failure(
                            CacheOperation.PUT, CacheResult.FailureKind.REDIS, null))
                    .withMessage("cause");
        }

        @Test
        @DisplayName("inserted and existing outcomes remain distinct")
        void putIfAbsentOutcomes_areDistinct() {
            assertThat(CacheResult.inserted().outcome()).isEqualTo(CacheResult.Outcome.INSERTED);
            assertThat(CacheResult.inserted().isSuccess()).isTrue();
            assertThat(CacheResult.existing(null).outcome()).isEqualTo(CacheResult.Outcome.EXISTING);
            assertThat(CacheResult.existing(null).isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("不可变与防御性复制")
    class ImmutabilityTests {

        @Test
        @DisplayName("构造后修改入参 byte[] 不影响结果(构造期防御复制)")
        void resultBytes_constructorDefensiveCopy() {
            byte[] mutable = "value".getBytes();
            CacheResult result = CacheResult.success(mutable);

            mutable[0] = 'X';

            assertThat(result.resultBytes()).isEqualTo("value".getBytes());
        }

        @Test
        @DisplayName("读取返回的 byte[] 修改不影响后续读取(访问期防御复制)")
        void resultBytes_accessorDefensiveCopy() {
            CacheResult result = CacheResult.success("value".getBytes());

            byte[] first = result.resultBytes();
            first[0] = 'X';

            assertThat(result.resultBytes()).isEqualTo("value".getBytes());
        }

        @Test
        @DisplayName("无 setter / builder — 反射确认字段 final,状态不可变")
        void noMutationApi_reflectsImmutable() throws Exception {
            assertThat(CacheResult.class.getConstructors())
                    .as("构造器必须私有(只能经工厂创建)")
                    .isEmpty();

            for (java.lang.reflect.Field field : CacheResult.class.getDeclaredFields()) {
                assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                        .as("字段 %s 必须 final", field.getName())
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("非法状态不可表示")
    class IllegalStateTests {

        @Test
        @DisplayName("无工厂可构造 failure-不带-kind / success-带-failure 等矛盾组合")
        void noContradictoryState_constructible() {
            // 唯一失败工厂签名强制 operation/kind/cause;
            // 无 failure() 无参 / failure(String,String) 工厂可被编译期拒绝。
            // 编译期不可表示 — 此处仅验证工厂形态(见上 failure_createsFailureResult)。
            assertThat(CacheResult.class.getMethods())
                    .filteredOn(m -> m.getName().equals("failure"))
                    .hasSize(1);
            assertThat(CacheResult.class.getMethods())
                    .filteredOn(m -> m.getName().equals("builder"))
                    .isEmpty();
        }

        @Test
        @DisplayName("equals/hashCode 覆盖 outcome + bytes(值语义)")
        void equalsHashCode_valueSemantics() {
            assertThat(CacheResult.success("v".getBytes()))
                    .isEqualTo(CacheResult.success("v".getBytes()));
            assertThat(CacheResult.success("v".getBytes()).hashCode())
                    .isEqualTo(CacheResult.success("v".getBytes()).hashCode());
            assertThat(CacheResult.success("a".getBytes()))
                    .isNotEqualTo(CacheResult.success("b".getBytes()));
            assertThat(CacheResult.miss()).isNotEqualTo(CacheResult.success());
        }

        @Test
        @DisplayName("toString 不含原始 key/异常消息(诊断安全)")
        void toString_noRawKeyOrMessage() {
            CacheResult failure = CacheResult.failure(
                    CacheOperation.PUT,
                    CacheResult.FailureKind.REDIS,
                    new IllegalStateException("secret-key raw message"));
            assertThat(failure.toString())
                    .doesNotContain("secret-key")
                    .doesNotContain("raw message")
                    .contains("PUT")
                    .contains("REDIS");
        }
    }
}
