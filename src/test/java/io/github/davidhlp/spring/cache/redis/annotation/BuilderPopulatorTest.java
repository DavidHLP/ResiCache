package io.github.davidhlp.spring.cache.redis.annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BuilderPopulator} deep seam contract tests — Round 50 / 架构评审候选 A.
 *
 * <p>钉住两个 helper 的语义:
 * <ul>
 *   <li>{@link BuilderPopulator#applyText} — null-safe 单字段写入</li>
 *   <li>{@link BuilderPopulator#populate} — text + special 编排,链式返回 builder</li>
 * </ul>
 *
 * <p>测试使用一个轻量 mock builder {@link RecordingBuilder} — 不依赖 Spring 标准 Builder,
 * 这样 seam 的契约(纯函数式,与具体 builder 类型无关)在没有 fixture 副作用的情况下被验证。
 * 端到端的"AnnotationParser / SpringAnnotationAdapter 接入 BuilderPopulator" 由现有
 * {@link SpringAnnotationAdapterTest} + {@link RedisCacheOperationSourceSelectiveTest}
 * + {@code AnnotatedElementPolymorphicSeamTest} 联合覆盖。
 */
@DisplayName("BuilderPopulator Seam Tests")
class BuilderPopulatorTest {

    /**
     * 极简 builder —— 字段值累积到 {@link #written} 列表供断言。
     * 不依赖任何 Spring/Lombok 类型,作为 seam 的真接口测试载体。
     */
    static final class RecordingBuilder {
        final List<String> written = new ArrayList<>();
        int counter = 0;

        RecordingBuilder setKey(String v) { written.add("key=" + v); return this; }
        RecordingBuilder setCondition(String v) { written.add("condition=" + v); return this; }
        RecordingBuilder setKeyGenerator(String v) { written.add("keyGenerator=" + v); return this; }
        RecordingBuilder bumpCounter() { counter++; return this; }
    }

    /** Mock 注解 source —— 函数引用从其读字段。 */
    static final class Anno {
        final String key;
        final String condition;
        final boolean sync;
        Anno(String key, String condition, boolean sync) {
            this.key = key; this.condition = condition; this.sync = sync;
        }
        String key() { return key; }
        String condition() { return condition; }
    }

    // -------- applyText --------

    @Nested
    @DisplayName("applyText(B, String, BiConsumer) — null-safe 单字段写入")
    class ApplyTextContract {

        @Test
        @DisplayName("value 非空时写入 builder")
        void writesWhenHasText() {
            RecordingBuilder b = new RecordingBuilder();
            BuilderPopulator.applyText(b, "hello", RecordingBuilder::setKey);
            assertThat(b.written).containsExactly("key=hello");
        }

        @Test
        @DisplayName("value 为 null 时跳过,不写入")
        void skipsWhenNull() {
            RecordingBuilder b = new RecordingBuilder();
            BuilderPopulator.applyText(b, null, RecordingBuilder::setKey);
            assertThat(b.written).isEmpty();
        }

        @Test
        @DisplayName("value 为空串时跳过(StringUtils.hasText 行为)")
        void skipsWhenEmpty() {
            RecordingBuilder b = new RecordingBuilder();
            BuilderPopulator.applyText(b, "", RecordingBuilder::setKey);
            assertThat(b.written).isEmpty();
        }

        @Test
        @DisplayName("value 为全空白时跳过(StringUtils.hasText 行为)")
        void skipsWhenWhitespace() {
            RecordingBuilder b = new RecordingBuilder();
            BuilderPopulator.applyText(b, "   ", RecordingBuilder::setKey);
            assertThat(b.written).isEmpty();
        }
    }

    // -------- populate --------

    @Nested
    @DisplayName("populate(B, A, List<TextField>, List<BiConsumer>) — text + special 编排")
    class PopulateContract {

        @Test
        @DisplayName("textFields 中非空字段写入,空字段跳过")
        void textFieldsConditionalApplication() {
            RecordingBuilder b = new RecordingBuilder();
            Anno ann = new Anno("k", null, false);

            BuilderPopulator.populate(b, ann,
                    List.of(
                            BuilderPopulator.TextField.textField(Anno::key, RecordingBuilder::setKey),
                            BuilderPopulator.TextField.textField(Anno::condition, RecordingBuilder::setCondition)),
                    List.of());

            assertThat(b.written).containsExactly("key=k");
        }

        @Test
        @DisplayName("textFields 全部非空时全部写入")
        void allTextFieldsAppliedWhenAllPresent() {
            RecordingBuilder b = new RecordingBuilder();
            Anno ann = new Anno("k", "c", false);

            BuilderPopulator.populate(b, ann,
                    List.of(
                            BuilderPopulator.TextField.textField(Anno::key, RecordingBuilder::setKey),
                            BuilderPopulator.TextField.textField(Anno::condition, RecordingBuilder::setCondition)),
                    List.of());

            assertThat(b.written).containsExactly("key=k", "condition=c");
        }

        @Test
        @DisplayName("specialFields 无条件应用(即使 annotation 字段为 false)")
        void specialFieldsAlwaysApplied() {
            RecordingBuilder b = new RecordingBuilder();
            Anno ann = new Anno(null, null, false);

            BuilderPopulator.populate(b, ann,
                    List.of(),
                    List.of((builder, a) -> builder.bumpCounter()));

            assertThat(b.counter).isEqualTo(1);
        }

        @Test
        @DisplayName("textFields 与 specialFields 混合:text 先,special 后")
        void mixedTextAndSpecialInOrder() {
            RecordingBuilder b = new RecordingBuilder();
            Anno ann = new Anno("k", null, true);

            BuilderPopulator.populate(b, ann,
                    List.of(
                            BuilderPopulator.TextField.textField(Anno::key, RecordingBuilder::setKey),
                            BuilderPopulator.TextField.textField(Anno::condition, RecordingBuilder::setCondition)),
                    List.of((builder, a) -> builder.bumpCounter()));

            assertThat(b.written).containsExactly("key=k");
            assertThat(b.counter).isEqualTo(1);
        }

        @Test
        @DisplayName("null textFields 视为空列表,不抛 NPE")
        void nullTextFieldsSafe() {
            RecordingBuilder b = new RecordingBuilder();
            Anno ann = new Anno("k", null, false);

            BuilderPopulator.populate(b, ann, null,
                    List.of((builder, a) -> builder.bumpCounter()));

            assertThat(b.counter).isEqualTo(1);
            assertThat(b.written).isEmpty();
        }

        @Test
        @DisplayName("null specialFields 视为空列表,不抛 NPE")
        void nullSpecialFieldsSafe() {
            RecordingBuilder b = new RecordingBuilder();
            Anno ann = new Anno("k", null, false);

            BuilderPopulator.populate(b, ann,
                    List.of(
                            BuilderPopulator.TextField.textField(Anno::key, RecordingBuilder::setKey)),
                    null);

            assertThat(b.written).containsExactly("key=k");
        }

        @Test
        @DisplayName("populate 返回 builder 自身,支持链式")
        void returnsBuilderForChaining() {
            RecordingBuilder b = new RecordingBuilder();
            Anno ann = new Anno(null, null, false);

            RecordingBuilder returned = BuilderPopulator.populate(b, ann, List.of(), List.of());

            assertThat(returned).isSameAs(b);
        }
    }

    // -------- TextField record --------

    @Nested
    @DisplayName("TextField<A,B> record + textField 工厂")
    class TextFieldRecordContract {

        @Test
        @DisplayName("textField 工厂返回的 record 持有 getter + setter")
        void factoryPreservesValueAndSetter() {
            BuilderPopulator.TextField<Anno, RecordingBuilder> tf =
                    BuilderPopulator.TextField.textField(Anno::key, RecordingBuilder::setKey);

            Anno ann = new Anno("hello", null, false);
            RecordingBuilder b = new RecordingBuilder();

            assertThat(tf.value().apply(ann)).isEqualTo("hello");
            tf.setter().accept(b, "hello");
            assertThat(b.written).containsExactly("key=hello");
        }

        @Test
        @DisplayName("record 构造器直接 new 与 textField 工厂产出行为等价")
        void recordConstructorEquivalent() {
            // 注:Function/BiConsumer lambda 实例无 equals 语义,record 字段相等 ≠ record 相等。
            // 本测试钉住"行为等价"而非"对象相等":两种构造方式调出同一 value + setter 行为。
            BuilderPopulator.TextField<Anno, RecordingBuilder> viaFactory =
                    BuilderPopulator.TextField.textField(Anno::key, RecordingBuilder::setKey);
            BuilderPopulator.TextField<Anno, RecordingBuilder> viaConstructor =
                    new BuilderPopulator.TextField<>(Anno::key, RecordingBuilder::setKey);

            Anno ann = new Anno("k", null, false);

            RecordingBuilder b1 = new RecordingBuilder();
            viaFactory.value().apply(ann);
            viaFactory.setter().accept(b1, viaFactory.value().apply(ann));

            RecordingBuilder b2 = new RecordingBuilder();
            viaConstructor.value().apply(ann);
            viaConstructor.setter().accept(b2, viaConstructor.value().apply(ann));

            assertThat(b1.written).isEqualTo(b2.written).containsExactly("key=k");
        }
    }
}
