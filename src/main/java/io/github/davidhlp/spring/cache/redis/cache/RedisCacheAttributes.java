package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
import lombok.Builder;
import lombok.Value;
import org.springframework.cache.interceptor.CacheEvictOperation;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CachePutOperation;
import org.springframework.cache.interceptor.CacheableOperation;

/** Immutable annotation projection shared by Spring AOP and the active read/write policy. */
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

    /** 方法级 TTL/秒；{@code 0} 表示回退调用方或全局 TTL */
    long ttl;

    /** 兼容声明元数据；不约束运行时值类型 */
    Class<?> type;

    /** 是否缓存空值防止缓存穿透（仅 Cacheable/Put 适用） */
    boolean cacheNullValues;

    /** 布隆过滤器配置：是否启用（容量由全局属性配置） */
    boolean useBloomFilter;

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

    /** Shared policy fields are assigned directly so every getter/setter pairing is typed. */
    private void applyPolicyCommonFields(RedisCacheAttributeSink b) {
        b.cacheNames(cacheNames);
        b.keyGenerator(keyGenerator);
        b.cacheManager(cacheManager);
        b.cacheResolver(cacheResolver);
        b.condition(condition);
        b.sync(sync);
        b.syncTimeout(syncTimeout);
        b.ttl(ttl);
        b.useBloomFilter(useBloomFilter);
        b.enableEarlyExpiration(enableEarlyExpiration);
        b.earlyExpirationThreshold(earlyExpirationThreshold);
        b.earlyExpirationMode(earlyExpirationMode);
    }

    public RedisCacheableOperation.Builder applyTo(RedisCacheableOperation.Builder b) {
        applyPolicyCommonFields(b);
        BuilderPopulator.applyText(b, unless, RedisCacheableOperation.Builder::unless);
        return b.type(type).cacheNullValues(cacheNullValues).randomTtl(randomTtl).variance(variance);
    }

    public RedisCachePutOperation.Builder applyTo(RedisCachePutOperation.Builder b) {
        applyPolicyCommonFields(b);
        BuilderPopulator.applyText(b, unless, RedisCachePutOperation.Builder::unless);
        return b.type(type).cacheNullValues(cacheNullValues).randomTtl(randomTtl).variance(variance);
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
