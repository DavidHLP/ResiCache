package io.github.davidhlp.spring.cache.redis.cache;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import lombok.experimental.UtilityClass;
import org.springframework.util.StringUtils;

/** Shared nonblank text assignment and typed Spring annotation builder mapping. */
@UtilityClass
final class BuilderPopulator {

    /**
     * 单个文本字段的描述 — value getter + builder setter 二元组.
     *
     * <p>{@code <A>}:注解类型(通常 3 个 ResiCache 注解之一);{@code <B>}:目标 builder 类型
     * (Spring 标准 {@code CacheableOperation.Builder} 或 Lombok 派生的
     * {@code RedisCachePutOperation.Builder} 等).
     *
     * <p>两元素都是函数引用,调用方只需声明"哪个字段取哪个注解 getter 写到哪个 builder setter"
     * —— 字段映射知识以纯声明形式承载,无需运行时反射.
     *
     * @param value    从注解对象读取该字段值的 getter({@link Function},类型 {@code A → String})
     * @param setter   写入 builder 的 setter({@link BiConsumer},类型 {@code (B, String) → void})
     * @param <A>      注解类型
     * @param <B>      builder 类型
     */
    public record TextField<A, B>(
            Function<A, String> value,
            BiConsumer<B, String> setter) {

        /**
         * 工厂方法 — 让 caller 写 {@code textField(Anno::key, B::setKey)} 而不是
         * {@code new TextField<>(Anno::key, B::setKey)},符合 Lombok Builder 风格的
         * 紧凑调用.
         */
        public static <A, B> TextField<A, B> textField(
                Function<A, String> value, BiConsumer<B, String> setter) {
            return new TextField<>(value, setter);
        }
    }

    /**
     * 字段填充编排 — 在 builder 上迭代 textFields(带 {@code hasText} 守卫) +
     * specialFields(无条件应用),返回 builder 自身供 caller 链式.
     *
     * <p>典型调用形态:
     * <pre>
     * Builder b = new Builder();
     * b.setName(name);
     * b.setCacheNames(cacheNames);
     * BuilderPopulator.populate(b, annotation,
     *     List.of(
     *         BuilderPopulator.TextField.textField(Anno::key,          B::setKey),
     *         BuilderPopulator.TextField.textField(Anno::condition,     B::setCondition),
     *         BuilderPopulator.TextField.textField(Anno::unless,        B::setUnless),
     *         BuilderPopulator.TextField.textField(Anno::keyGenerator,  B::setKeyGenerator),
     *         BuilderPopulator.TextField.textField(Anno::cacheManager,  B::setCacheManager),
     *         BuilderPopulator.TextField.textField(Anno::cacheResolver, B::setCacheResolver)
     *     ),
     *     List.of((builder, anno) -&gt; builder.setSync(anno.sync())));
     * return b.build();
     * </pre>
     *
     * <p>调用方负责在调本方法<b>之前</b>设置 name + cacheNames(这两字段 setX 签名各 builder
     * 略有差异,且"value vs cacheNames 合并"逻辑由 caller 决定,本 seam 不感知).
     *
     * @param builder        目标 builder(已 setName + setCacheNames)
     * @param annotation     注解实例(取值来源)
     * @param textFields     文本字段列表(getter + setter);{@code null} 视为空列表
     * @param specialFields  special 字段列表(setter);{@code null} 视为空列表
     * @param <A>            注解类型
     * @param <B>            builder 类型
     * @return 同一 builder(链式调用)
     */
    public static <A, B> B populate(
            B builder,
            A annotation,
            List<TextField<A, B>> textFields,
            List<BiConsumer<B, A>> specialFields) {

        if (textFields != null) {
            for (TextField<A, B> tf : textFields) {
                applyText(builder, tf.value().apply(annotation), tf.setter());
            }
        }
        if (specialFields != null) {
            for (BiConsumer<B, A> sf : specialFields) {
                sf.accept(builder, annotation);
            }
        }
        return builder;
    }

    /**
     * 单字段 null-safe 写入 — {@code value} 非空(经 {@link StringUtils#hasText} 判定)
     * 时调 setter,空/null 时跳过.
     *
     * <p>本方法用 {@link BiConsumer} 把 builder 也传入,允许 setter 在 lambda 体内捕获 builder
     * 实例(适配 Lombok 链式 builder 写法).
     *
     * <p>{@link RedisCacheAttributes} 与 {@link SpringAnnotationAdapter} 的
     * {@code if (StringUtils.hasText(...)) builder.setX(...);} 样板
     * 经本方法统一处理,消除重复的 if-守卫.
     *
     * @param builder 目标 builder
     * @param value   待写入值(null 或空串时跳过)
     * @param setter  写入 setter
     * @param <B>     builder 类型
     */
    public static <B> void applyText(B builder, String value, BiConsumer<B, String> setter) {
        if (StringUtils.hasText(value)) {
            setter.accept(builder, value);
        }
    }
}
