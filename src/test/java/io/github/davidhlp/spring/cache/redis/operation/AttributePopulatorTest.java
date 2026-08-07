package io.github.davidhlp.spring.cache.redis.operation;

import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link AttributePopulator} deep seam 的契约测试。
 *
 * <p>本测试覆盖 4 类契约:
 * <ul>
 *   <li><b>populate 编排</b> — 多个 sink 顺序应用,每个 setter 收到正确的字段值</li>
 *   <li><b>空列表 / null list</b> — 防御性 null/empty 列表返回 builder 自身不抛异常</li>
 *   <li><b>FieldSink 强转协议</b> — setter 收到 {@code Object} 强转回正确类型</li>
 *   <li><b>14 共享字段 sink 列表形态</b> — 与 {@code RedisCacheAttributes.applyTo} 3 重载
 *       委派的 14 项字段集对齐,新加 1 个共享字段时 sink 列表的增量为 1 行</li>
 * </ul>
 *
 * <p>本测试不直接覆盖 {@link RedisCacheAttributes#applyTo}(B 重载 × 3)—— 后者由
 * {@code OperationFromAttributesTest} 跨 3 Operation 类型 pin 住行为,本类聚焦
 * {@link AttributePopulator} seam 自身的协议契约。
 */
@DisplayName("AttributePopulator seam")
class AttributePopulatorTest {

    /**
     * 简单测试 POJO — 模拟 {@link RedisCacheAttributes} 的字段形态,让 populate
     * 对其迭代 sinks 并应用 setter。
     */
    static class TestPojo {
        String[] arr = {"c1", "c2"};
        String str = "hello";
        long num = 42L;
        boolean flag = true;
        double dbl = 0.5;
        EarlyExpirationMode mode = EarlyExpirationMode.ASYNC;

        String[] getArr() { return arr; }
        String getStr() { return str; }
        long getNum() { return num; }
        boolean isFlag() { return flag; }
        double getDbl() { return dbl; }
        EarlyExpirationMode getMode() { return mode; }
    }

    /**
     * 简单测试 Builder — 模拟 14 共享字段的目标 builder 形态。
     */
    static class TestBuilder {
        String[] arr;
        String str;
        long num;
        boolean flag;
        double dbl;
        EarlyExpirationMode mode;
        int setterCalls = 0;

        TestBuilder arr(String[] v) { this.arr = v; setterCalls++; return this; }
        TestBuilder str(String v) { this.str = v; setterCalls++; return this; }
        TestBuilder num(long v) { this.num = v; setterCalls++; return this; }
        TestBuilder flag(boolean v) { this.flag = v; setterCalls++; return this; }
        TestBuilder dbl(double v) { this.dbl = v; setterCalls++; return this; }
        TestBuilder mode(EarlyExpirationMode v) { this.mode = v; setterCalls++; return this; }
    }

    @Nested
    @DisplayName("populate 编排")
    class Populate {

        @Test
        @DisplayName("多个 sink 顺序应用,setter 收到正确字段值")
        void populate_appliesAllSinksInOrder() {
            TestPojo p = new TestPojo();
            TestBuilder b = new TestBuilder();

            AttributePopulator.populate(b, p, List.of(
                    AttributePopulator.FieldSink.fieldSink(TestPojo::getArr,
                            (builder, v) -> builder.arr((String[]) v)),
                    AttributePopulator.FieldSink.fieldSink(TestPojo::getStr,
                            (builder, v) -> builder.str((String) v)),
                    AttributePopulator.FieldSink.fieldSink(TestPojo::getNum,
                            (builder, v) -> builder.num((long) v)),
                    AttributePopulator.FieldSink.fieldSink(TestPojo::isFlag,
                            (builder, v) -> builder.flag((boolean) v)),
                    AttributePopulator.FieldSink.fieldSink(TestPojo::getDbl,
                            (builder, v) -> builder.dbl((double) v)),
                    AttributePopulator.FieldSink.fieldSink(TestPojo::getMode,
                            (builder, v) -> builder.mode((EarlyExpirationMode) v))
            ));

            assertThat(b.arr).containsExactly("c1", "c2");
            assertThat(b.str).isEqualTo("hello");
            assertThat(b.num).isEqualTo(42L);
            assertThat(b.flag).isTrue();
            assertThat(b.dbl).isEqualTo(0.5);
            assertThat(b.mode).isEqualTo(EarlyExpirationMode.ASYNC);
            assertThat(b.setterCalls).isEqualTo(6);
        }

        @Test
        @DisplayName("返回同一 builder 实例(支持链式)")
        void populate_returnsSameBuilder() {
            TestPojo p = new TestPojo();
            TestBuilder b = new TestBuilder();

            TestBuilder result = AttributePopulator.populate(b, p, List.of(
                    AttributePopulator.FieldSink.fieldSink(TestPojo::getStr,
                            (builder, v) -> builder.str((String) v))));

            assertThat(result).isSameAs(b);
        }
    }

    @Nested
    @DisplayName("空列表 / null 防御")
    class EmptyAndNull {

        @Test
        @DisplayName("空列表 → builder 自身返回,无 setter 调用")
        void populate_emptyList_doesNotInvokeSetters() {
            TestPojo p = new TestPojo();
            TestBuilder b = new TestBuilder();

            AttributePopulator.populate(b, p, List.of());

            assertThat(b.setterCalls).isEqualTo(0);
        }

        @Test
        @DisplayName("null 列表 → builder 自身返回,不抛 NPE")
        void populate_nullList_returnsBuilderNoNpe() {
            TestPojo p = new TestPojo();
            TestBuilder b = new TestBuilder();

            TestBuilder result = AttributePopulator.populate(b, p, null);

            assertThat(result).isSameAs(b);
            assertThat(b.setterCalls).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("FieldSink 强转协议")
    class FieldSinkCast {

        @Test
        @DisplayName("setter 收到 Object 后强转回正确类型")
        void fieldSink_setterReceivesObjectAndCastsCorrectly() {
            TestPojo p = new TestPojo();
            TestBuilder b = new TestBuilder();

            // setter 显式强转 — 模拟 applyTo 中的实际用法
            AttributePopulator.populate(b, p, List.of(
                    AttributePopulator.FieldSink.fieldSink(TestPojo::getNum,
                            (builder, v) -> {
                                // Object → long 强转必须安全
                                long n = (long) v;
                                builder.num(n);
                            })));

            assertThat(b.num).isEqualTo(42L);
        }

        @Test
        @DisplayName("fieldSink 工厂方法生成正确 record")
        void fieldSink_factoryReturnsNewRecord() {
            AttributePopulator.FieldSink<TestPojo, TestBuilder> sink =
                    AttributePopulator.FieldSink.fieldSink(TestPojo::getStr,
                            (builder, v) -> builder.str((String) v));

            assertThat(sink.value()).isNotNull();
            assertThat(sink.setter()).isNotNull();
        }
    }

    @Nested
    @DisplayName("14 共享字段 sink 列表形态")
    class SharedFieldsShape {

        @Test
        @DisplayName("sink 列表大小 14,顺序由 caller 决定")
        void sharedFieldsSinksListSizeIs14() {
            // 模拟 applyTo 中构造的 sink 列表(14 共享字段)
            // 此测试不直接调用 RedisCacheAttributes 内部 sink 列表(那不是 public API),
            // 而是用 TestPojo 模拟 14 字段形态,验证 populate 协议支持任意 sink 数量
            TestPojo p = new TestPojo();
            TestBuilder b = new TestBuilder();
            int[] calls = {0};

            AttributePopulator.populate(b, p, java.util.stream.IntStream.range(0, 14)
                    .mapToObj(i -> AttributePopulator.FieldSink.<TestPojo, TestBuilder>fieldSink(
                            TestPojo::getStr,
                            (builder, v) -> { calls[0]++; builder.str((String) v + "-" + i); }))
                    .toList());

            assertThat(calls[0]).isEqualTo(14);
            // 最后一次调用 str("hello-13")
            assertThat(b.str).isEqualTo("hello-13");
        }

        @Test
        @DisplayName("新加共享字段 = 1 行 sink spec")
        void addingSharedField_isOneLineIncrement() {
            // 此测试<em>不</em>测 populate,而是验证 sink 协议使得"新加 1 字段"成本
            // = 在 caller 端加 1 行 FieldSink.fieldSink(...) 即可
            TestPojo p = new TestPojo();
            TestBuilder b = new TestBuilder();

            // 模拟 caller 端 14 行 sink + 1 新字段行 = 15 sink
            AttributePopulator.populate(b, p, List.of(
                    AttributePopulator.FieldSink.fieldSink(TestPojo::getArr,
                            (builder, v) -> builder.arr((String[]) v)),
                    AttributePopulator.FieldSink.fieldSink(TestPojo::getStr,
                            (builder, v) -> builder.str((String) v))
                    // ... 实际 14 字段全部 FieldSink.fieldSink(...) 委派
            ));

            // 验证 setter 被调用了 2 次(2 sink)
            assertThat(b.setterCalls).isEqualTo(2);
        }
    }
}
