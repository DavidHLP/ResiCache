package io.github.davidhlp.spring.cache.redis.operation;

import io.github.davidhlp.spring.cache.redis.factory.RedisCacheAttributesProjector;
import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
import lombok.Builder;
import lombok.Value;

/**
 * Redis 缓存注解的<strong>内部投影层</strong>：把三个公开注解（{@code @RedisCacheable} /
 * {@code @RedisCachePut} / {@code @RedisCacheEvict}）和 Spring 的 {@code @Cacheable}
 * 映射到一个统一的<em>语义相同的</em>值对象上。
 *
 * <p>本类是工厂层和链之间传递数据的<strong>唯一事实来源（single source of truth）</strong>：
 * <ul>
 *   <li>字段默认值（如 {@link #syncTimeout} / {@link #expectedInsertions} /
 *       {@link #falseProbability}）由 {@link RedisCacheAttributesProjector} 集中收敛，
 *       任何注解都不再持有自己的"分散默认";</li>
 *   <li>三个具体 factory 与 Spring 适配 factory 都消费本类，消除了原"18/18 builder 字段
 *       逐字重复"（{@code Cacheable ≡ Put}）以及"3 处默认值漂移"的死代码与认知负担;</li>
 *   <li>新增字段只动本类 + 投影器 + 1 个 Builder.fromAttributes 三处，而非 9 处。</li>
 * </ul>
 *
 * <p>Evict 独有字段（{@link #allEntries} / {@link #beforeInvocation}）也包含在本类中，
 * 由具体 Operation 的 {@code fromAttributes} 方法选择性使用；语义在 Evict 不适用的字段对
 * 其他注解不设任何限制。
 *
 * <p><strong>包归属</strong>：放在 {@code operation} 包而非 {@code factory} 包 —
 * 本类是对"ResiCache operation 数据形状"的统一描述，{@code fromAttributes(method, key, attributes)}
 * 三个 operation 类的静态工厂方法直接消费本类。包方向保持 {@code factory → operation} 单向
 * （factory 通过本类 import 注入数据，operation 通过 {@code fromAttributes} 静态方法完成
 * Builder 填充，二者均不需对方反向依赖）。
 *
 * <p><strong>public by package</strong>：仅 factory 与 projector 内部使用，未声明 public
 * 构造器；外部应通过 {@link RedisCacheAttributesProjector} 构造。
 *
 * @see RedisCacheAttributesProjector
 * @see RedisCacheableOperation#fromAttributes(java.lang.reflect.Method, String, RedisCacheAttributes)
 * @see RedisCachePutOperation#fromAttributes(java.lang.reflect.Method, String, RedisCacheAttributes)
 * @see RedisCacheEvictOperation#fromAttributes(java.lang.reflect.Method, String, RedisCacheAttributes)
 */
@Value
@Builder(toBuilder = true)
public class RedisCacheAttributes {

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
}
