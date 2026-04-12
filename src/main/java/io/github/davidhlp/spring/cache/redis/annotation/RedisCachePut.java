package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshMode;

import java.lang.annotation.*;

/**
 * 缓存更新注解
 *
 * <p>用于标注方法，每次执行方法后强制更新缓存，而非检查缓存是否存在。
 * 适用于数据更新后需要立即刷新缓存的场景。
 *
 * <p>与 @RedisCacheable 的区别：
 * <ul>
 *   <li>@RedisCacheable：先检查缓存，缓存命中则返回缓存值</li>
 *   <li>@RedisCachePut：每次都执行方法并更新缓存</li>
 * </ul>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface RedisCachePut {

    /**
     * 缓存名称，与 Spring Cache 的 value 相同
     */
    String[] value() default {};

    /**
     * 缓存名称别名，与 value 相同
     */
    String[] cacheNames() default {};

    /**
     * 缓存 key，支持 SpEL 表达式
     * <p>例如：#id, #user.id, '#user.' + #user.name
     */
    String key() default "";

    /**
     * 自定义 key 生成器 bean 名称
     */
    String keyGenerator() default "";

    /**
     * 自定义 cacheManager bean 名称
     */
    String cacheManager() default "";

    /**
     * 自定义 cacheResolver bean 名称
     */
    String cacheResolver() default "";

    /**
     * 缓存条件，支持 SpEL 表达式，为 true 时才缓存
     */
    String condition() default "";

    /**
     * 不缓存的条件，支持 SpEL 表达式，为 true 时跳过缓存
     */
    String unless() default "";

    /**
     * 缓存过期时间（秒）
     * <p>设置该值后，缓存将在指定时间后过期
     */
    long ttl() default 60;

    /**
     * 缓存值的类型
     * <p>用于反序列化时的类型检查
     */
    Class<?> type() default Object.class;

    /**
     * 是否缓存空值防止缓存穿透
     */
    boolean cacheNullValues() default false;

    /**
     * 是否使用布隆过滤器防缓存穿透
     */
    boolean useBloomFilter() default false;

    /**
     * 布隆过滤器预期插入数量
     */
    long expectedInsertions() default 100000;

    /**
     * 布隆过滤器误判率
     */
    double falseProbability() default 0.01;

    /**
     * 是否使用分布式锁防缓存击穿
     */
    boolean sync() default false;

    /**
     * 同步锁超时时间（秒）
     * <p>-1 表示使用全局配置
     */
    long syncTimeout() default -1;

    /**
     * 是否使用 TTL 随机化防缓存雪崩
     */
    boolean randomTtl() default false;

    /**
     * TTL 随机化范围
     * <p>实际 TTL = originalTtl * (1 ± variance)
     * <p>例如：variance=0.2 表示 TTL 范围为 [original*0.8, original*1.2]
     */
    float variance() default 0.2F;

    /**
     * 是否启用预刷新
     * <p>在缓存过期前主动刷新，避免缓存雪崩
     */
    boolean enablePreRefresh() default false;

    /**
     * 预刷新阈值
     * <p>当缓存剩余 TTL 低于 (ttl * preRefreshThreshold) 时触发预刷新
     * <p>例如：0.3 表示剩余 30% TTL 时触发
     */
    double preRefreshThreshold() default 0.3;

    /**
     * 预刷新模式
     * <p>SYNC: 同步刷新，ASYNC: 异步刷新
     */
    PreRefreshMode preRefreshMode() default PreRefreshMode.SYNC;
}
