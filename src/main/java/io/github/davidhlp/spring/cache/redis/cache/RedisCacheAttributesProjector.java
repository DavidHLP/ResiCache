package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;

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
 * <p><strong>公开注解字段签名不变</strong>。本类只负责“注解属性 → RedisCacheAttributes”的
 * 投影；共享字段的 builder 映射由 {@link RedisCacheAttributeSink} 与
 * {@code RedisCacheAttributes#COMMON_SINKS} 协同维护，三个 operation builder 分别实现该契约。
 *
 * <p>Spring 原生 {@code @Cacheable} 由 {@link SpringCacheableAdapter} 内部直接构造，
 * 无需投影层。
 *
 * <p><b>seam 收敛</b>：{@link AnnotationParser} 对每个注解只调用本类一次，得到的
 * {@link RedisCacheAttributes} 同时喂给 AOP 面与 policy 面 —— 本类是
 * "注解 → operation 字段" 的唯一映射，两侧不再各自读注解、各自解释同一字段。
 * 三个 {@code from(annotation)} 公共面之下，共享字段的 builder 链下沉到单一
 * {@code project(FieldSource, boolean, boolean)} 方法 + 三个轻量
 * {@code extractFrom(annotation)} 提取器，共享一份 builder 链。
 * Cacheable / Put 的 Evict-only 字段显式传 {@code false}，Evict 则传入注解值。
 *
 * <p><b>新增一个共享字段的真实触点</b>（以 {@code useBloomFilter} 的 grep 口径实测,
 * 2026-09 复核:{@code grep -rn 'useBloomFilter\|UseBloomFilter' src/main/java} = 35 行 /
 * 14 文件,其中 2 行是读取方、6 行在本类）:3 个注解声明 + 本类
 * {@link FieldSource} 组件 + {@code project()} 一行 + 3 个 {@code extractFrom()} 参数 +
 * {@link RedisCacheAttributes} 字段 + {@link RedisCacheAttributeSink} 方法 +
 * {@code COMMON_SINKS} 一行 + 3 个 policy Builder 的字段/setter + 3 个 Operation 构造赋值 +
 * {@code CachePolicyView} 链路。本类只收敛其中 6 行;其余是编译期强制的适配器
 * （漏改即编译失败）,不是可漂移的重复映射。AOP 面共享字段（{@code cacheNames / key /
 * keyGenerator / cacheManager / cacheResolver / condition}）的映射各自只有
 * {@link RedisCacheAttributes#applyTo(org.springframework.cache.interceptor.CacheableOperation.Builder)}
 * 一个声明点,与三个注解族无关。
 *
 * <p><b>{@code expectedInsertions} 类型契约已统一</b>：三个公开注解均使用 {@code long}
 * 并以 {@code 100000L} 为默认值，与 {@link RedisCacheAttributes#expectedInsertions}
 * 一致。本投影器只做无差别映射，不执行隐式拓宽或窄化。
 *
 * <p><b>非 bean</b>：本类与 {@link SpringCacheableAdapter} 由 {@link AnnotationParser}
 * 直接 {@code new} 构造 —— 它们是解析器的内部协作器,不是可替换的扩展点。此前二者标注
 * {@code @Component} 但没有任何注入方（唯一的外部构造点已随两参构造器一并删除）,
 * 2026-09 复核后去掉注解。
 *
 */
class RedisCacheAttributesProjector {

    /**
     * 从 {@link RedisCacheable} 投影。
     * <p>注：{@code value} 与 {@code cacheNames} 合并——同时声明两者时 {@code value} 优先
     * （见 {@link #resolveCacheNames}）。
     */
    public RedisCacheAttributes from(RedisCacheable annotation) {
        return annotation == null ? null : project(extractFrom(annotation), false, false);
    }

    /**
     * 从 {@link RedisCachePut} 投影。
     */
    public RedisCacheAttributes from(RedisCachePut annotation) {
        return annotation == null ? null : project(extractFrom(annotation), false, false);
    }

    /**
     * 从 {@link RedisCacheEvict} 投影。
     * {@code unless} 在 Evict 注解中存在，但 Evict 的 Builder 没有 {@code unless} 槽位——
     * 这里保留字段用于兼容性投影，但当前 Spring CacheEvictOperation 路径不会评估该属性。
     * 需要条件清除时使用 {@code condition}；未来激活 {@code unless} 必须另行定义并验证语义。
     * <p>Evict 不持有 {@code type / cacheNullValues / randomTtl / variance} 字段（无对应
     * 业务语义），由 {@link #extractFrom(RedisCacheEvict)} 填入合理默认
     * （{@code Object.class / false / false / 0.0F}）。
     */
    public RedisCacheAttributes from(RedisCacheEvict annotation) {
        if (annotation == null) {
            return null;
        }
        return project(extractFrom(annotation),
                annotation.allEntries(), annotation.beforeInvocation());
    }

    // ---------------------------------------------------------------------
    // seam: 共享字段 + Evict-only delta 收敛到 project(...)
    // ---------------------------------------------------------------------

    /**
     * 22 字段的统一容器——任意 {@code @RedisCache*} 注解的"标准化字段快照"。
     *
     * <p>存在意义：Java 注解类型不可共享接口，无法用 {@code extends} / {@code default}
     * 方法提取公共读取路径。本 record 把 3 个注解的 22 字段统一成一个 type-safe 容器，
     * 让统一投影方法能以单一 builder 链消费三种
     * 来源，避免在 3 个 {@code from(annotation)} 内重复同一份 22 字段链。
     *
     * <p>字段顺序与 {@link #project} body 内 builder 调用顺序一致，便于审计"字段→属性"
     * 映射。新增字段时同步：{@link FieldSource} 组件 + {@code project()} body +
     * {@code extractFrom(annotation)}（按字段在注解中是否真实存在决定是否需要更新）。
     *
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
     * 单一 builder 链 seam：22 字段从 {@link FieldSource} 流入 {@link RedisCacheAttributes}，
     * Evict-only 字段由调用方显式传入。
     *
     * <p><b>唯一权威 builder 链</b>——3 个 {@code from(annotation)} 都收敛到此方法，
     * 任何字段读取/写入错误都会被一次修改覆盖所有来源。{@code cacheNames} 与 {@code value}
     * 在此处走 {@link #resolveCacheNames} 合并。
     */
    private static RedisCacheAttributes project(
            FieldSource f, boolean allEntries, boolean beforeInvocation) {
        var b = RedisCacheAttributes.builder()
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
                .syncTimeout(f.syncTimeout())
                .allEntries(allEntries)
                .beforeInvocation(beforeInvocation);
        return b.build();
    }

    /**
     * 把 {@link RedisCacheable} 注解的字段读入 {@link FieldSource}。
     * <p>Cacheable 与 Put 的字段集在投影层完全同构——22 字段逐一对应。
     * <p>{@code annotation.expectedInsertions()} 是 {@code long}，直接映射到
     * {@code FieldSource.expectedInsertions} 的 {@code long} 槽位，不存在 int 截断。
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
     * false / 0.0F}）。{@code ttl} Evict 持有但语义不同
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
     * 解析缓存名称：{@code value} 优先，为空则用 {@code cacheNames}。
     *
     * <p>这是 {@code main} 上 AOP 面的既有语义（三个 {@code parseRedisCache*} 均写
     * {@code ann.value().length > 0 ? ann.value() : ann.cacheNames()}），c6 统一两面后本方法
     * 是唯一的解析点，因此 {@code value} 必须在两面上都赢——否则同时声明两者的注解会让
     * policy 面指向一个实际未被使用的 cache。
     *
     * <p>只声明其中一个时行为不变：另者为空数组，直接由非空的那一个决定。
     */
    public static String[] resolveCacheNames(String[] cacheNames, String[] value) {
        if (value != null && value.length > 0) {
            return value;
        }
        return cacheNames != null ? cacheNames : new String[0];
    }
}
