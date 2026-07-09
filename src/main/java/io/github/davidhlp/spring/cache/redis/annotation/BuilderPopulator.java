package io.github.davidhlp.spring.cache.redis.annotation;

import lombok.experimental.UtilityClass;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 注解 → Builder 字段填充的 deep seam — Round 50 / 架构评审候选 A 抽出.
 *
 * <p><b>problem (背景)</b>:两条注解 → Spring {@code CacheOperation} 解析路径
 * ({@link AnnotationParser} 解析 {@code @RedisCacheable/@RedisCacheEvict/@RedisCachePut},
 *  {@link SpringAnnotationAdapter} 解析 Spring {@code @Cacheable/@CachePut/@CacheEvict})
 * 各持有 3 个近镜像的 builder 方法,共 6 处.每个方法都遵循同一形状:
 * <ol>
 *   <li>new Builder()</li>
 *   <li>{@code setName(name)}</li>
 *   <li>{@code setCacheNames(value-or-cacheNames)}</li>
 *   <li>6 个文本字段({@code key / condition / unless / keyGenerator / cacheManager /
 *       cacheResolver})逐个做{@code if (hasText) setX} 守卫式赋值 — 18 处 in
 *       {@code AnnotationParser} + 17 处 in {@code SpringAnnotationAdapter}</li>
 *   <li>1-2 个 special 字段({@code sync} / {@code cacheWide} / {@code beforeInvocation})直接赋值</li>
 *   <li>{@code build()}</li>
 * </ol>
 *
 * <p>两类的实现各自把同一形状重写一次 — 添加 1 个新 ResiCache 注解字段需同时改两个类的
 * 3 个方法共 6 个触点,且 {@code AnnotationParser} 不会复用 {@code SpringAnnotationAdapter}
 * 已有的私有 {@code applyText} helper.同一形状在两文件中独立漂移.
 *
 * <p><b>solution</b>:把"形状 → 字段填充"收口到本类两个 seam:
 * <ul>
 *   <li>{@link #applyText(Object, String, BiConsumer)} — 单字段 null-safe 写入,
 *       替换 {@code if (hasText) b.setX(value)} 样板.原 {@code SpringAnnotationAdapter}
 *       的私有 {@code applyText} 迁移到此,两类的 35 处 if-守卫收敛为一处.</li>
 *   <li>{@link #populate(Object, Object, List, List)} — 整个 builder 的字段填充
 *       编排:迭代 textFields(应用 {@code applyText}) + 迭代 specialFields(直接应用).
 *       每个 parse/build 方法退化为 1 个 populate(...) 调用 + 1 个 build().</li>
 * </ul>
 *
 * <p><b>name + cacheNames 不在 populate 范围内</b>:这两个字段的 setter 类型/语义各 Builder
 * 一致(setName(String) + setCacheNames(String[])),且 parse/build 方法各自有不同的"value
 * vs cacheNames 合并"逻辑 — 抽出后增加 2 个参数(name + cacheNames)与 2 个 setter
 * (BiConsumer)的传递成本大于收益.由 caller 预 set name + cacheNames,本类只负责
 * "text + special"两阶段编排.
 *
 * <p><b>deletion test</b>:删本类 + 内联回两个 caller →
 * <ul>
 *   <li>{@link AnnotationParser} 6 个 parse 方法 × 18 if-守卫 = 35+ 行样板回归</li>
 *   <li>{@link SpringAnnotationAdapter} 私有 {@code applyText} 重新出现 + 3 个 build 方法
 *       17 个 applyText 调用恢复</li>
 *   <li>两文件继续持有"同一形状"2 份独立实现,字段新增触点 = 6 个 caller 方法</li>
 * </ul>
 * seam 挣得起存在代价(单类 ~60 SLOC 含 Javadoc).
 *
 * <p><b>包归属</b>:放在 {@code annotation} 包 — {@link AnnotationParser} /
 * {@link SpringAnnotationAdapter} 是本 utility 的两个生产 consumer,utility 自身无 domain
 * 依赖(纯 {@code StringUtils} + 标准 JDK functional API).
 *
 * <p><b>不可变性</b>:
 * <ul>
 *   <li>{@link UtilityClass}(Lombok)生成 private 构造 + final class 阻止实例化</li>
 *   <li>helper 全为 {@code public static},无状态,线程安全</li>
 * </ul>
 *
 * @see AnnotationParser
 * @see SpringAnnotationAdapter
 */
@UtilityClass
public final class BuilderPopulator {

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
     * <p>迁移自 {@code SpringAnnotationAdapter} 私有 helper(原签名
     * {@code applyText(String, Consumer<String>)},只能传递 setter 而非 builder);
     * 本方法用 {@link BiConsumer} 把 builder 也传入,允许 setter 在 lambda 体内捕获 builder
     * 实例(适配 Lombok 链式 builder 写法).
     *
     * <p>{@link AnnotationParser} 18 处
     * {@code if (StringUtils.hasText(ann.x())) builder.setX(ann.x());} 样板
     * 全部改为本方法调用,消除重复的 if-守卫.
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
