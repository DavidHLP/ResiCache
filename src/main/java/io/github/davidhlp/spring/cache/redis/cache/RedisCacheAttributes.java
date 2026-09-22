package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import lombok.Builder;
import lombok.Value;
import org.springframework.cache.interceptor.CacheEvictOperation;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CachePutOperation;
import org.springframework.cache.interceptor.CacheableOperation;

/**
 * Redis 缓存注解的<strong>内部投影层</strong>：把三个公开注解（{@code @RedisCacheable} /
 * {@code @RedisCachePut} / {@code @RedisCacheEvict}）和 Spring 的 {@code @Cacheable}
 * 映射到一个统一的<em>语义相同的</em>值对象上。
 *
 * <p>本类是投影器与三个 Redis operation builder 之间传递数据的<strong>统一运行时字段载体</strong>：
 * <ul>
 *   <li>三个公开注解各自声明语义相关的默认值；其中 {@code syncTimeout}、
 *       {@code expectedInsertions} 和 {@code falseProbability} 这三个共享契约字段按统一值
 *       规范化。本类承载投影后的运行时字段，由 {@link RedisCacheAttributesProjector} 完成
 *       注解属性到值对象的映射，并供三个 Redis operation builder 填充;</li>
 *   <li>统一的值对象和共享 builder sink 消除了原"18/18 builder 字段逐字重复"
 *       （{@code Cacheable ≡ Put}）以及三处默认值分别声明造成的漂移风险与认知负担;</li>
 *   <li>共享运行时字段的映射集中在本类、投影器和 Builder.fromAttributes 协作点，避免在
 *       三个 operation builder 中重复完整映射链。</li>
 * </ul>
 *
 * <p>Evict 独有字段（{@link #allEntries} / {@link #beforeInvocation}）也包含在本类中，
 * 由具体 Operation 的 {@code fromAttributes} 方法选择性使用；语义在 Evict 不适用的字段对
 * 其他注解不设任何限制。
 *
 * <p><strong>内部协作</strong>：本类位于 {@code io.github.davidhlp.spring.cache.redis.cache}
 * 包，作为投影器与三个 Redis operation 静态工厂方法之间共享的 operation 数据形状。
 * 三个 operation 类的 {@code fromAttributes(method, key, attributes)} 工厂直接消费本类；
 * 投影器负责生成属性值对象，operation builder 负责将字段填入 Spring operation。
 * 协作关系保持在同一 cache 包内，不依赖不存在的 {@code factory} 或 {@code operation} 子包。
 *
 * <p><strong>package-private</strong>：仅 cache 包内的投影器和 operation 工厂使用，未声明
 * public 构造器；注解属性应通过 {@link RedisCacheAttributesProjector} 进入投影路径。
 *
 * <p><strong>{@code applyTo(B)} seam</strong>：本类将三个 Operation 的
 * {@code fromAttributes} 字段映射委托给 {@code COMMON_SINKS} 与各 {@code applyTo(B)}
 * 重载；共享字段的 setter 契约由 {@link RedisCacheAttributeSink} 统一声明，差异字段由
 * 各重载末尾的链式 setter 处理。
 *
 * <p><strong>两面一源</strong>：{@code applyTo} 重载同时覆盖 policy 面（{@code Redis*Operation.Builder}）
 * 与 AOP 面（Spring 原生 {@code CacheableOperation.Builder} 等）。同一个注解只投影成本类
 * 一个实例，两面再从这个实例派生，因此 AOP operation 与 policy operation 对同一字段
 * 不可能给出不同解释。
 *
 * <p><strong>共享字段 vs 差异字段</strong>: 14 个共享字段由本类的 {@code COMMON_SINKS}
 * 与 {@code populate} 统一迭代；差异字段由各 {@code applyTo} 重载末尾链式 setter 管理。
 * 差异字段中，Evict 缺 {@code unless/type/cacheNullValues/randomTtl/variance} 5 项，
 * Cacheable/Put 缺 {@code allEntries/beforeInvocation} 2 项；它们因 builder-only 性质
 * 保留在各 {@code applyTo} 重载末尾。与 {@code BuilderPopulator} 形成的两道 seam
 * 互不耦合。
 *
 * @see RedisCacheAttributesProjector
 * @see RedisCacheableOperation#fromAttributes(java.lang.reflect.Method, String, RedisCacheAttributes)
 * @see RedisCachePutOperation#fromAttributes(java.lang.reflect.Method, String, RedisCacheAttributes)
 * @see RedisCacheEvictOperation#fromAttributes(java.lang.reflect.Method, String, RedisCacheAttributes)
 */
@Value
@Builder(toBuilder = true)
class RedisCacheAttributes {

    /** 缓存名称（与 Spring 的 {@code value} 同义；投影器已合并两条路径） */
    String[] cacheNames;

    /** 缓存 key，SpEL / 字面量；运行时若空则回退到 {@code KeyGenerator} */
    String key;
    String keyGenerator;
    String cacheManager;
    String cacheResolver;
    String condition;
    String unless;

    /** TTL/秒；{@code 0}（Evict 语义）表示"不设置过期" */
    long ttl;

    /** 缓存值的类型（默认 {@link Object}） */
    Class<?> type;

    /** 是否缓存空值防止缓存穿透（仅 Cacheable/Put 适用） */
    boolean cacheNullValues;

    /** 布隆过滤器配置：是否启用 / 预期插入数 / 误判率 */
    boolean useBloomFilter;
    long expectedInsertions;
    double falseProbability;

    /** TTL 随机化（防雪崩） */
    boolean randomTtl;
    float variance;

    /** 提前过期（防击穿）的阈值与模式 */
    boolean enableEarlyExpiration;
    double earlyExpirationThreshold;
    EarlyExpirationMode earlyExpirationMode;

    /** 同步锁（细粒度防击穿） */
    boolean sync;
    long syncTimeout;

    /** Evict-only：是否清除所有缓存项 */
    boolean allEntries;

    /** Evict-only：是否在方法执行前清除 */
    boolean beforeInvocation;
    private record FieldSink<A, B>(
            Function<A, ?> value,
            BiConsumer<B, Object> setter) {

        private static <A, B> FieldSink<A, B> fieldSink(
                Function<A, ?> value, BiConsumer<B, Object> setter) {
            return new FieldSink<>(value, setter);
        }
    }

    private static <A, B> B populate(
            B builder,
            A pojo,
            List<FieldSink<A, B>> sinks) {
        for (FieldSink<A, B> sink : sinks) {
            sink.setter().accept(builder, sink.value().apply(pojo));
        }
        return builder;
    }


    // ============================ applyTo(B) seam ============================

    /**
     * 14 个共享字段的<strong>单一真相</strong>。
     *
     * <p>三个 {@code applyTo} 重载各自调用本类的 {@code populate} 后,
     * 追加各自的 builder-only 差异字段。新增一个共享字段只需增加一行 sink spec
     * 和一个 {@link RedisCacheAttributeSink} 方法；漏加 sink 方法的 builder 在编译期报错。
     */
    private static final List<FieldSink<RedisCacheAttributes, RedisCacheAttributeSink>> COMMON_SINKS =
            List.of(
                    FieldSink.fieldSink(RedisCacheAttributes::getCacheNames,
                            (builder, v) -> builder.cacheNames((String[]) v)),
                    FieldSink.fieldSink(RedisCacheAttributes::getKeyGenerator,
                            (builder, v) -> builder.keyGenerator((String) v)),
                    FieldSink.fieldSink(RedisCacheAttributes::getCacheManager,
                            (builder, v) -> builder.cacheManager((String) v)),
                    FieldSink.fieldSink(RedisCacheAttributes::getCacheResolver,
                            (builder, v) -> builder.cacheResolver((String) v)),
                    FieldSink.fieldSink(RedisCacheAttributes::getCondition,
                            (builder, v) -> builder.condition((String) v)),
                    FieldSink.fieldSink(RedisCacheAttributes::isSync,
                            (builder, v) -> builder.sync((boolean) v)),
                    FieldSink.fieldSink(RedisCacheAttributes::getSyncTimeout,
                            (builder, v) -> builder.syncTimeout((long) v)),
                    FieldSink.fieldSink(RedisCacheAttributes::getTtl,
                            (builder, v) -> builder.ttl((long) v)),
                    FieldSink.fieldSink(RedisCacheAttributes::isUseBloomFilter,
                            (builder, v) -> builder.useBloomFilter((boolean) v)),
                    FieldSink.fieldSink(RedisCacheAttributes::getExpectedInsertions,
                            (builder, v) -> builder.expectedInsertions((long) v)),
                    FieldSink.fieldSink(RedisCacheAttributes::getFalseProbability,
                            (builder, v) -> builder.falseProbability((double) v)),
                    FieldSink.fieldSink(RedisCacheAttributes::isEnableEarlyExpiration,
                            (builder, v) -> builder.enableEarlyExpiration((boolean) v)),
                    FieldSink.fieldSink(RedisCacheAttributes::getEarlyExpirationThreshold,
                            (builder, v) -> builder.earlyExpirationThreshold((double) v)),
                    FieldSink.fieldSink(RedisCacheAttributes::getEarlyExpirationMode,
                            (builder, v) -> builder.earlyExpirationMode((EarlyExpirationMode) v))
            );

    /**
     * 把本 POJO 全部 22 字段映射到 {@link RedisCacheableOperation.Builder}。
     *
     * <p>本方法<strong>唯一拥有</strong>"22 字段 → Builder" 的映射知识;
     * 三个 Operation.fromAttributes 单行委派到本方法。
     *
     * <p>{@code expectedInsertions} 在 Cacheable Builder 是 {@code long} 槽位
     * (与 Put/Evict 对齐),直传无窄化。
     *
     * <p>14 共享字段由本类的 {@code populate} 填充 —— 新增字段只需增加一个 sink spec。
     * 差异字段(unless/type/cacheNullValues/randomTtl/variance 5 项)因 builder-only 性质
     * 保留在本方法末尾链式 setter 中。
     *
     * @param b 已有 {@code name} / {@code key} 设值的 builder(由 fromAttributes 传入)
     * @return 同一 builder(支持链式)
     */
    public RedisCacheableOperation.Builder applyTo(RedisCacheableOperation.Builder b) {
        // 14 共享字段填充走本类 COMMON_SINKS 单一真相;
        // sink 列表仅此一份,三个 applyTo 重载共享,漂移由 RedisCacheAttributeSink 拦截。
        populate(b, this, COMMON_SINKS);
        // Cacheable-only 5 字段:builder-only,不出现在其他两个 applyTo 重载
        return b
                .unless(unless)
                .type(type)
                .cacheNullValues(cacheNullValues)
                .randomTtl(randomTtl)
                .variance(variance);
    }

    /**
     * 把本 POJO 全部 22 字段映射到 {@link RedisCachePutOperation.Builder}。
     *
     * <p>Cacheable/Put 字段类型完全一致 — both builders 用 {@code long} 槽位承载
     * {@code expectedInsertions},直传无窄化。
     *
     * <p>14 共享字段列表由本类的 {@code populate} 单一 seam 承载 —— 本方法
     * 与 {@link #applyTo(RedisCacheableOperation.Builder)} 共享同一填充协议;差异字段
     * (unless/type/cacheNullValues/randomTtl/variance 5 项,与 Cacheable 同集)保留在
     * 本方法末尾链式 setter 中。
     *
     * @param b 已有 {@code name} / {@code key} 设值的 builder
     * @return 同一 builder(支持链式)
     */
    public RedisCachePutOperation.Builder applyTo(RedisCachePutOperation.Builder b) {
        // 14 共享字段填充走本类 COMMON_SINKS 单一真相
        populate(b, this, COMMON_SINKS);
        // Put-only 5 字段(与 Cacheable 同集):
        return b
                .unless(unless)
                .type(type)
                .cacheNullValues(cacheNullValues)
                .randomTtl(randomTtl)
                .variance(variance);
    }

    /**
     * 把本 POJO 的 14 共享字段 + 2 Evict-only 字段映射到
     * {@link RedisCacheEvictOperation.Builder}。
     *
     * <p>Evict 是 Cacheable/Put 的<em>子集 + Evict-only</em>:
     * <ul>
     *   <li><strong>缺失</strong>(语义不适用,Evict 不持有 builder 槽位):{@code unless} /
     *       {@code type} / {@code cacheNullValues} / {@code randomTtl} / {@code variance}</li>
     *   <li><strong>Evict-only</strong> 直传:{@code allEntries} / {@code beforeInvocation}</li>
     * </ul>
     *
     * <p>14 共享字段列表由本类的 {@code populate} 单一 seam 承载 —— 本方法
     * 与其他两个 applyTo 重载共享同一填充协议;差异字段(allEntries / beforeInvocation 2 项)
     * 保留在本方法末尾链式 setter 中(注:Evict Builder 的 {@code allEntries} setter 内部委托
     * 给 Spring 父类的 {@code setCacheWide})。
     *
     * @param b 已有 {@code name} / {@code key} 设值的 builder
     * @return 同一 builder(支持链式)
     */
    public RedisCacheEvictOperation.Builder applyTo(RedisCacheEvictOperation.Builder b) {
        // 14 共享字段填充走本类 COMMON_SINKS 单一真相
        populate(b, this, COMMON_SINKS);
        // Evict-only 2 字段(委托给父类 setCacheWide / setBeforeInvocation)
        return b
                .allEntries(allEntries)
                .beforeInvocation(beforeInvocation);
    }

    // ======================= AOP 面适配器 =======================

    /**
     * AOP 面共享字段的<b>单一</b>声明 —— 三副 Spring builder 的共同父类
     * {@link CacheOperation.Builder} 承载 {@code cacheNames} + 6 文本字段,故只写一遍;
     * 文本字段保留 AOP 路径一贯的 {@code hasText} 守卫(Spring builder 视空串为"未设置")。
     *
     * <p>新增一个 AOP 面共享字段 = 本方法 1 行,三个注解族同时生效。
     */
    private void applyToSpringCommonFields(CacheOperation.Builder b) {
        b.setCacheNames(cacheNames);
        BuilderPopulator.applyText(b, key, CacheOperation.Builder::setKey);
        BuilderPopulator.applyText(b, condition, CacheOperation.Builder::setCondition);
        BuilderPopulator.applyText(b, keyGenerator, CacheOperation.Builder::setKeyGenerator);
        BuilderPopulator.applyText(b, cacheManager, CacheOperation.Builder::setCacheManager);
        BuilderPopulator.applyText(b, cacheResolver, CacheOperation.Builder::setCacheResolver);
    }

    /**
     * 本值对象的 AOP 面 → {@link CacheableOperation.Builder}。
     *
     * <p><b>为什么 AOP 面不能直接用 policy op</b>:{@code CacheAspectSupport.CacheOperationContexts}
     * 以 {@code op.getClass()} 为桶键(按 {@code CacheableOperation.class} /
     * {@code CachePutOperation.class} / {@code CacheEvictOperation.class} 取用),所以 AOP 面
     * 必须是 Spring 原生 operation —— {@link RedisCacheableOperation} 会落进没有读取方的桶。
     * 两面因此都从<b>同一份</b>{@link RedisCacheAttributes} 派生:同一个注解只投影一次,
     * AOP 面与 policy 面对同一字段不可能给出不同解释。{@code name} 由 caller 预置。
     *
     * <p>注:传入 {@link RedisCacheableOperation.Builder} 时重载解析选中更具体的 policy 面
     * {@link #applyTo(RedisCacheableOperation.Builder)} —— 编译期规则,非隐式行为。
     */
    public CacheableOperation.Builder applyTo(CacheableOperation.Builder b) {
        applyToSpringCommonFields(b);
        BuilderPopulator.applyText(b, unless, CacheableOperation.Builder::setUnless);
        b.setSync(sync);
        return b;
    }

    /**
     * 本值对象的 AOP 面 → {@link CachePutOperation.Builder}。
     *
     * <p>{@code sync} 不是 Spring {@code CachePutOperation} 的概念,故不进这一面(仍进 policy 面)。
     */
    public CachePutOperation.Builder applyTo(CachePutOperation.Builder b) {
        applyToSpringCommonFields(b);
        BuilderPopulator.applyText(b, unless, CachePutOperation.Builder::setUnless);
        return b;
    }

    /**
     * 本值对象的 AOP 面 → {@link CacheEvictOperation.Builder}。
     *
     * <p>Cacheable/Put 面的子集 + Evict-only:{@code unless} 无槽位,{@code allEntries} /
     * {@code beforeInvocation} 落进 Spring 的 {@code cacheWide} / {@code beforeInvocation}。
     */
    public CacheEvictOperation.Builder applyTo(CacheEvictOperation.Builder b) {
        applyToSpringCommonFields(b);
        b.setCacheWide(allEntries);
        b.setBeforeInvocation(beforeInvocation);
        return b;
    }
}
