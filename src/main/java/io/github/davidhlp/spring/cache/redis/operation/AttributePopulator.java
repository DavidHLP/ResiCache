package io.github.davidhlp.spring.cache.redis.operation;

import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * {@link RedisCacheAttributes} → Operation Builder 的字段填充 deep seam —
 * Round 51 / 架构评审候选 A 抽出.
 *
 * <p><b>problem (背景)</b>:{@link RedisCacheAttributes} 暴露 3 个 {@code applyTo(B)} 重载
 * (Cacheable/Put/Evict 三种 Builder 类型).每个重载都遵循同一形状:
 * <ol>
 *   <li>14 个<em>共享字段</em>({@code cacheNames / keyGenerator / cacheManager / cacheResolver /
 *       condition / sync / syncTimeout / ttl / useBloomFilter / expectedInsertions /
 *       falseProbability / enableEarlyExpiration / earlyExpirationThreshold / earlyExpirationMode})
 *       逐个调用 builder 对应 setter — <b>14 行 setter 链在 3 个重载中重复 3 遍 = 42 行</b></li>
 *   <li>1-2 个<em>builder-only 差异字段</em>(Cacheable/Put 5 项,Evict 2 项)直接调用</li>
 *   <li>return builder(链式)</li>
 * </ol>
 *
 * <p>原代码把 14 共享字段的"共享列表"放在 {@code COMMON_FIELD_NAMES} <b>Javadoc</b> 里
 * 当作"文字"单一来源 — 新增 1 个共享字段需改 Javadoc + 3 个重载各加 1 行 setter = 4 触点;
 * 共享列表的实质"形状"是注释,不是代码,无法编译期约束.
 *
 * <p><b>solution</b>:把"形状 → 字段填充"收口到本类的两个 seam:
 * <ul>
 *   <li>{@link FieldSink} — 单个<em>类型无关</em>的字段描述:
 *       {@code <A> Function<A, ?> value} + {@code <B> BiConsumer<B, Object> setter}.
 *       setX 接受 {@code Object} 让 14 字段混合类型(int / long / boolean / double / String / String[] /
 *       EarlyExpirationMode)可放在同一 List 内,无需 14 个 generic 专用 sink.</li>
 *   <li>{@link #populate(Object, Object, List)} — 整个 builder 的 14 共享字段迭代应用;
 *       单一调用方,3 个 applyTo 重载退化到 "1 行 populate(...) + 1-2 行差异字段 setter" 的形状.</li>
 * </ul>
 *
 * <p><b>与 {@code BuilderPopulator} 的关系</b>:Round 50 的
 * {@code io.github.davidhlp.spring.cache.redis.annotation.BuilderPopulator} 走
 * Spring 标准 {@code CacheableOperation.Builder} 的 {@code setX} 命名(setX 接受 String),
 * 处理"annotation → Spring builder"的字段填充.
 * 本类面向 Operation 侧的 Lombok 链式 builder(如 {@code RedisCacheableOperation.Builder.x}
 * 命名)与 Spring 标准 builder 混合 — 同一形状但 setter 签名不同(链式 vs Spring setX).
 * 二者分别承担"注解 → Spring builder"与"attributes → Operation builder"两道 seam,
 * 互不耦合.
 *
 * <p><b>name + cacheNames 不在 populate 范围</b>:这 2 字段由 caller 在调 populate 之前
 * 已 set(每 caller 在 fromAttributes 入口 setName + setKey),与 {@code BuilderPopulator} 一致.
 * "value vs cacheNames 合并"逻辑各 caller 略不同(cacheNames 来自 POJO 字段),由 caller 自行处理.
 *
 * <p><b>deletion test</b>:删本类 + 内联回 3 个 applyTo 重载 →
 * <ul>
 *   <li>14 setter × 3 重载 = 42 行重复 setter 链恢复</li>
 *   <li>共享字段列表再次沦为 Javadoc 注释,失去编译期约束</li>
 *   <li>新加 1 个共享字段触点 4 处(Javadoc + 3 重载)</li>
 * </ul>
 * seam 挣得起存在代价(单类 ~50 SLOC 含 Javadoc).
 *
 * <p><b>包归属</b>:放在 {@code operation} 包 — {@link RedisCacheAttributes} 是本 utility
 * 唯一生产 consumer,本类无 domain 依赖(纯 JDK functional API).
 *
 * <p><b>不可变性</b>:{@link UtilityClass}(Lombok)生成 private 构造 + final class 阻止实例化;
 * helper 全为 {@code public static},无状态,线程安全.
 *
 * @see RedisCacheAttributes
 * @see io.github.davidhlp.spring.cache.redis.annotation.BuilderPopulator
 */
@UtilityClass
public final class AttributePopulator {

    /**
     * 单个字段的描述 — 从 POJO 读 value + 写入 builder 的 setter.
     *
     * <p>{@code <A>}:来源 POJO 类型(本类唯一 caller = {@link RedisCacheAttributes});
     * {@code <B>}:目标 builder 类型(3 个 Operation Builder 之一).
     *
     * <p>setter 签名 {@code BiConsumer<B, Object>} 接受 {@code Object} 而非字段具体类型 —
     * 14 共享字段类型(int / long / boolean / double / String / String[] / EarlyExpirationMode)
     * 异质,需在 setter 内部做强转.强转在 builder setter 调用点必然安全(类型与字段定义对齐),
     * ClassCastException 是 caller-side 错配,运行期立即暴露.
     *
     * <p>value getter 用 {@code Function<A, ?>} 接受任意返回类型 — 同理,14 字段返回类型异质.
     *
     * @param value    从 POJO 读取该字段值的 getter
     * @param setter   写入 builder 的 setter(接受 {@code Object} 后强转为 builder setter 实际参数类型)
     * @param <A>      POJO 类型
     * @param <B>      builder 类型
     */
    public record FieldSink<A, B>(
            Function<A, ?> value,
            BiConsumer<B, Object> setter) {

        /**
         * 工厂方法 — 让 caller 写 {@code fieldSink(A::field, B::setter)} 而不是
         * {@code new FieldSink<>(A::field, B::setter)},符合 Lombok Builder 风格.
         *
         * <p>{@code setter} 必须能接受 {@code Object} 类型实参 — 在 lambda 体内做
         * unchecked 强转,运行期由 builder 自身的类型校验兜底.
         */
        public static <A, B> FieldSink<A, B> fieldSink(
                Function<A, ?> value, BiConsumer<B, Object> setter) {
            return new FieldSink<>(value, setter);
        }
    }

    /**
     * 字段填充编排 — 在 builder 上迭代 sinks(每个 sink 把对应 POJO 字段值写入 builder),
     * 返回 builder 自身供 caller 链式.
     *
     * <p>典型调用形态:
     * <pre>
     * Builder b = builder().name(name).key(key);
     * AttributePopulator.populate(b, a, sinksOf(b));
     * return b.builderOnlyFieldX(a.x()).build();
     * </pre>
     *
     * <p>本方法不做 {@code hasText} 守卫(attributes 全部为非空字段或带 Builder @Builder.Default
     * 默认值);空值/0/false 由 Builder 默认接管,无 {@code null → setX} 报 IAE 风险.
     *
     * @param builder  目标 builder(已 setName + setKey)
     * @param pojo     来源 POJO(读取字段值)
     * @param sinks    字段描述列表(每个 sink = getter + builder-setter 二元组);{@code null} 视为空列表
     * @param <A>      POJO 类型
     * @param <B>      builder 类型
     * @return 同一 builder(链式调用)
     */
    public static <A, B> B populate(
            B builder,
            A pojo,
            List<FieldSink<A, B>> sinks) {

        if (sinks == null) {
            return builder;
        }
        for (FieldSink<A, B> sink : sinks) {
            sink.setter().accept(builder, sink.value().apply(pojo));
        }
        return builder;
    }
}
