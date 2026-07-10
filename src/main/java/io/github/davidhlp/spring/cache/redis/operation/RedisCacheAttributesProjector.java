package io.github.davidhlp.spring.cache.redis.operation;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;

import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;

/**
 * 把 {@code @RedisCacheable / @RedisCachePut / @RedisCacheEvict} 三个公开注解的属性
 * 投影到统一的 {@link RedisCacheAttributes} 值对象上。
 *
 * <p><strong>本类是"单一字段映射 seam"——不改任何用户可见默认值</strong>：3 处历史漂移
 * （{@code syncTimeout / expectedInsertions / falseProbability}）的修复实际发生在
 * 三个注解的 {@code @interface} 默认值上（{@code RedisCacheable.expectedInsertions=
 * 100000} / {@code falseProbability=0.01}；{@code @RedisCachePut/Evict.syncTimeout=10}），
 * 而本投影器只做"注解 → 属性"的无差别映射，不再做隐含的 sentinel 归一化。
 *
 * <p><strong>公开注解字段签名不变</strong>。新增字段只动 {@link RedisCacheAttributes} +
 * 本投影器 + 1 个 Builder 三处，而非 9 处。
 *
 * <p>Spring 原生 {@code @Cacheable} 由 {@link SpringCacheableAdapterFactory} 内部直接构造，
 * 无需投影层。
 *
 * <p><b>ADR-0019 seam 收敛</b>：三个 {@code from(annotation)} 公共面之下，22 个共享字段
 * 的 builder 链已下沉到单一 {@link #project(FieldSource, UnaryOperator) project} 方法
 * + 三个轻量 {@code extractFrom(annotation)} 提取器。{@code from(Cacheable)} 与
 * {@code from(Put)} 共享 {@link #NO_EVICT_DELTA}（{@code @Builder} 默认 allEntries /
 * beforeInvocation = false），{@code from(Evict)} 自带 Evict-only delta 函数。后续新增
 * 共享字段：1 处改 {@link FieldSource} + 1 处改 {@code project()} body + 3 处改
 * {@code extractFrom()}（或部分子集），不再三处重复同一份 builder 链。
 *
 * <p><b>ADR-0019 已知 type-drift（不修，记录在 ADR）</b>：{@code @RedisCacheable.
 * expectedInsertions} 当前为 {@code int}，{@code @RedisCachePut} / {@code @RedisCacheEvict}
 * 与 {@link RedisCacheAttributes#expectedInsertions} 均为 {@code long}。投影器靠 Java
 * 隐式 {@code int→long} 拓宽把字段写入统一 {@code long} 容器——表面无 bug，但公开注解
 * 字段类型不一致。按 {@code STABILITY.md} §1 注解属性类型契约，本类型不静默修复，留待
 * 1.0 毕业时（§4）显式开 BREAKING 变更统一。详见 ADR-0019。
 */
@Component
public class RedisCacheAttributesProjector {

    /**
     * 从 {@link RedisCacheable} 投影。
     * <p>注：{@code cacheNames} 与 {@code value} 合并——{@code cacheNames} 优先、为空则用 {@code value}。
     */
    public RedisCacheAttributes from(RedisCacheable annotation) {
        return annotation == null ? null : project(extractFrom(annotation), NO_EVICT_DELTA);
    }

    /**
     * 从 {@link RedisCachePut} 投影。
     */
    public RedisCacheAttributes from(RedisCachePut annotation) {
        return annotation == null ? null : project(extractFrom(annotation), NO_EVICT_DELTA);
    }

    /**
     * 从 {@link RedisCacheEvict} 投影。
     * <p>{@code unless} 在 Evict 注解中存在，但 Evict 的 Builder 没有 {@code unless} 槽位——
     * 这里保留字段（语义一致），由 Evict 路径调用方（{@code EvictAnnotationHandler} /
     * {@code CachingAnnotationHandler} 的内联 lambda）自行决定是否应用。
     * <p>Evict 不持有 {@code type / cacheNullValues / randomTtl / variance} 字段（无对应
     * 业务语义），由 {@link #extractFrom(RedisCacheEvict)} 填入合理默认
     * （{@code Object.class / false / false / 0.0F}），与 v0.0.3 行为完全一致。
     */
    public RedisCacheAttributes from(RedisCacheEvict annotation) {
        if (annotation == null) {
            return null;
        }
        return project(
                extractFrom(annotation),
                b -> b.allEntries(annotation.allEntries())
                     .beforeInvocation(annotation.beforeInvocation()));
    }

    // ---------------------------------------------------------------------
    // ADR-0019 seam: 3 共享字段 + Evict-only delta 收敛到 project(...)
    // ---------------------------------------------------------------------

    /**
     * 22 字段的统一容器——任意 {@code @RedisCache*} 注解的"标准化字段快照"。
     *
     * <p>存在意义：Java 注解类型不可共享接口，无法用 {@code extends} / {@code default}
     * 方法提取公共读取路径。本 record 把 3 个注解的 22 字段统一成一个 type-safe 容器，
     * 让 {@link #project(FieldSource, UnaryOperator)} 能以单一 builder 链消费三种
     * 来源，避免在 3 个 {@code from(annotation)} 内重复同一份 22 字段链。
     *
     * <p>字段顺序与 {@link #project} body 内 builder 调用顺序一致，便于审计"字段→属性"
     * 映射。新增字段时同步：{@link FieldSource} 组件 + {@code project()} body +
     * {@code extractFrom(annotation)}（按字段在注解中是否真实存在决定是否需要更新）。
     *
     * @see #project(FieldSource, UnaryOperator)
     */
    private record FieldSource(
            String[] cacheNames,
            String[] value,
            String key,
            String keyGenerator,
            String cacheManager,
            String cacheResolver,
            String condition,
            String unless,
            long ttl,
            Class<?> type,
            boolean cacheNullValues,
            boolean useBloomFilter,
            long expectedInsertions,
            double falseProbability,
            boolean randomTtl,
            float variance,
            boolean enableEarlyExpiration,
            double earlyExpirationThreshold,
            EarlyExpirationMode earlyExpirationMode,
            boolean sync,
            long syncTimeout) {}

    /**
     * 共享的 Evict-only delta 函数：Cacheable / Put 不持有 {@code allEntries} /
     * {@code beforeInvocation} 字段，{@code @Builder} 默认 false，故传 no-op 函数。
     */
    private static final UnaryOperator<RedisCacheAttributes.RedisCacheAttributesBuilder> NO_EVICT_DELTA = b -> b;

    /**
     * 单一 builder 链 seam：22 字段从 {@link FieldSource} 流入 {@link RedisCacheAttributes.RedisCacheAttributesBuilder}，
     * Evict-only delta（{@code allEntries / beforeInvocation}）由调用方函数注入。
     *
     * <p><b>唯一权威 builder 链</b>——3 个 {@code from(annotation)} 都收敛到此方法，
     * 任何字段读取/写入错误都会被一次修改覆盖所有来源。{@code cacheNames} 与 {@code value}
     * 在此处走 {@link #resolveCacheNames} 合并。
     */
    private static RedisCacheAttributes project(
            FieldSource f, UnaryOperator<RedisCacheAttributes.RedisCacheAttributesBuilder> evictDelta) {
        RedisCacheAttributes.RedisCacheAttributesBuilder b = RedisCacheAttributes.builder()
                .cacheNames(resolveCacheNames(f.cacheNames(), f.value()))
                .key(f.key())
                .keyGenerator(f.keyGenerator())
                .cacheManager(f.cacheManager())
                .cacheResolver(f.cacheResolver())
                .condition(f.condition())
                .unless(f.unless())
                .ttl(f.ttl())
                .type(f.type())
                .cacheNullValues(f.cacheNullValues())
                .useBloomFilter(f.useBloomFilter())
                .expectedInsertions(f.expectedInsertions())
                .falseProbability(f.falseProbability())
                .randomTtl(f.randomTtl())
                .variance(f.variance())
                .enableEarlyExpiration(f.enableEarlyExpiration())
                .earlyExpirationThreshold(f.earlyExpirationThreshold())
                .earlyExpirationMode(f.earlyExpirationMode())
                .sync(f.sync())
                .syncTimeout(f.syncTimeout());
        evictDelta.apply(b);
        return b.build();
    }

    /**
     * 把 {@link RedisCacheable} 注解的字段读入 {@link FieldSource}。
     * <p>Cacheable 与 Put 的字段集在投影层完全同构——22 字段逐一对应。
     * <p><b>类型漂移提醒</b>：{@code annotation.expectedInsertions()} 是 {@code int}，
     * 经 Java 隐式拓宽到 {@code FieldSource.expectedInsertions} 的 {@code long} 槽位。
     * 调用方对超过 {@code Integer.MAX_VALUE} 的值会先在 javac 阶段被截断。
     */
    private static FieldSource extractFrom(RedisCacheable annotation) {
        return new FieldSource(
                annotation.cacheNames(),
                annotation.value(),
                annotation.key(),
                annotation.keyGenerator(),
                annotation.cacheManager(),
                annotation.cacheResolver(),
                annotation.condition(),
                annotation.unless(),
                annotation.ttl(),
                annotation.type(),
                annotation.cacheNullValues(),
                annotation.useBloomFilter(),
                annotation.expectedInsertions(),
                annotation.falseProbability(),
                annotation.randomTtl(),
                annotation.variance(),
                annotation.enableEarlyExpiration(),
                annotation.earlyExpirationThreshold(),
                annotation.earlyExpirationMode(),
                annotation.sync(),
                annotation.syncTimeout());
    }

    /**
     * 把 {@link RedisCachePut} 注解的字段读入 {@link FieldSource}。
     * <p>与 {@link #extractFrom(RedisCacheable)} 字段集同构；保留为单独方法而非
     * {@code extractFrom(annotation)} 多态，是因 Java 注解类型不可共享接口。
     * <p>{@code annotation.expectedInsertions()} 是 {@code long}，无隐式拓宽。
     */
    private static FieldSource extractFrom(RedisCachePut annotation) {
        return new FieldSource(
                annotation.cacheNames(),
                annotation.value(),
                annotation.key(),
                annotation.keyGenerator(),
                annotation.cacheManager(),
                annotation.cacheResolver(),
                annotation.condition(),
                annotation.unless(),
                annotation.ttl(),
                annotation.type(),
                annotation.cacheNullValues(),
                annotation.useBloomFilter(),
                annotation.expectedInsertions(),
                annotation.falseProbability(),
                annotation.randomTtl(),
                annotation.variance(),
                annotation.enableEarlyExpiration(),
                annotation.earlyExpirationThreshold(),
                annotation.earlyExpirationMode(),
                annotation.sync(),
                annotation.syncTimeout());
    }

    /**
     * 把 {@link RedisCacheEvict} 注解的字段读入 {@link FieldSource}。
     *
     * <p>Evict 注解缺 4 字段：{@code type / cacheNullValues / randomTtl / variance}——Evict
     * 不持有这些语义，按"Evict 不缓存值"前提填入合理默认（{@code Object.class / false /
     * false / 0.0F}），与 v0.0.3 行为完全一致。{@code ttl} Evict 持有但语义不同
     * （{@code 0} = 不设置过期），原样传入。
     *
     * <p>Evict-only 字段（{@code allEntries / beforeInvocation}）<strong>不</strong>走
     * {@link FieldSource}——本类不持有对应属性（{@code RedisCacheAttributes} 持有，但
     * 由 {@code from(RedisCacheEvict)} 的 lambda 直接调 Builder 写入，保留 Evict 字段
     * 来源在 Evict 调用方本地的 locality）。
     */
    private static FieldSource extractFrom(RedisCacheEvict annotation) {
        return new FieldSource(
                annotation.cacheNames(),
                annotation.value(),
                annotation.key(),
                annotation.keyGenerator(),
                annotation.cacheManager(),
                annotation.cacheResolver(),
                annotation.condition(),
                annotation.unless(),
                annotation.ttl(),
                Object.class,         // Evict 注解无 type 字段
                false,                // Evict 注解无 cacheNullValues 字段
                annotation.useBloomFilter(),
                annotation.expectedInsertions(),
                annotation.falseProbability(),
                false,                // Evict 注解无 randomTtl 字段
                0.0F,                 // Evict 注解无 variance 字段
                annotation.enableEarlyExpiration(),
                annotation.earlyExpirationThreshold(),
                annotation.earlyExpirationMode(),
                annotation.sync(),
                annotation.syncTimeout());
    }

    // ---------------------------------------------------------------------
    // 共享工具
    // ---------------------------------------------------------------------

    /**
     * 解析缓存名称：{@code cacheNames} 优先，为空则用 {@code value}。
     * 这是原三个注解共有的语义——{@code value} 与 {@code cacheNames} 同义，
     * Spring 的 {@code @Cacheable} 也遵循同一约定。
     */
    public static String[] resolveCacheNames(String[] cacheNames, String[] values) {
        if (cacheNames != null && cacheNames.length > 0) {
            return cacheNames;
        }
        return values != null ? values : new String[0];
    }
}
