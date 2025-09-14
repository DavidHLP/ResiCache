package com.david.spring.cache.redis.annotation;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * Redis缓存注解，用于标记方法返回值需要被缓存到Redis中。
 *
 * <p>该注解可以应用于类或方法级别，用于控制方法返回值的缓存行为。 缓存的实现依赖于Spring Cache抽象，同时增加了对Redis特有的过期时间（TTL）支持。
 * 使用本注解的方法在首次调用时会执行方法体并将结果缓存，后续调用直接返回缓存结果。
 *
 * <h3>使用示例：</h3>
 *
 * <pre>{@code
 * // 基础用法：缓存用户信息，默认60秒过期
 * @RedisCacheable("userCache")
 * public User getUserById(Long userId) { ... }
 *
 * // 指定缓存键和过期时间
 * @RedisCacheable(value = "userCache", key = "#user.id", ttl = 300)
 * public User getUser(User user) { ... }
 *
 * // 使用条件缓存
 * @RedisCacheable(value = "userCache",
 *                condition = "#user.age > 18",
 *                unless = "#result == null")
 * public User getAdultUser(User user) { ... }
 * }</pre>
 *
 * <h3>注解属性说明：</h3>
 *
 * <table border="1">
 *   <tr><th>属性</th><th>类型</th><th>说明</th><th>默认值</th></tr>
 *   <tr><td>value/cacheNames</td><td>String[]</td><td>缓存名称，可指定一个或多个缓存</td><td>{}</td></tr>
 *   <tr><td>key</td><td>String</td><td>SpEL表达式，生成缓存键</td><td>""</td></tr>
 *   <tr><td>keyGenerator</td><td>String</td><td>自定义键生成器Bean名称</td><td>""</td></tr>
 *   <tr><td>cacheManager</td><td>String</td><td>缓存管理器Bean名称</td><td>""</td></tr>
 *   <tr><td>cacheResolver</td><td>String</td><td>缓存解析器Bean名称</td><td>""</td></tr>
 *   <tr><td>condition</td><td>String</td><td>条件缓存SpEL表达式</td><td>""</td></tr>
 *   <tr><td>unless</td><td>String</td><td>条件不缓存SpEL表达式</td><td>""</td></tr>
 *   <tr><td>sync</td><td>boolean</td><td>是否同步加载缓存</td><td>false</td></tr>
 *   <tr><td>ttl</td><td>long</td><td>缓存过期时间(秒)</td><td>60</td></tr>
 *   <tr><td>type</td><td>Class&lt;?&gt;</td><td>缓存值类型</td><td>Object.class</td></tr>
 * </table>
 *
 * @author David <lysf15520112973@163.com>
 * @version 1.0.0
 * @since 2025-09-11
 * @see org.springframework.cache.annotation.Cacheable
 * @see org.springframework.data.redis.cache.RedisCacheConfiguration
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Cacheable
public @interface RedisCacheable {

    /**
     * 缓存名称别名，与 {@link #cacheNames()} 互为别名。
     *
     * <p>指定要使用的缓存名称，可以配置多个缓存名称。 当指定多个缓存名称时，方法返回值会被缓存到所有指定的缓存中。
     *
     * @return 缓存名称数组
     * @see #cacheNames()
     */
    @AliasFor(annotation = Cacheable.class, attribute = "value")
    String[] value() default {};

    /**
     * 缓存名称列表，与 {@link #value()} 互为别名。
     *
     * <p>此属性与{@link #value()}功能相同，提供更直观的语义。 当同时指定了{@code value}和{@code cacheNames}时，{@code
     * value}属性优先。
     *
     * @return 缓存名称数组
     * @see #value()
     */
    @AliasFor(annotation = Cacheable.class, attribute = "cacheNames")
    String[] cacheNames() default {};

    /**
     * 缓存键表达式，支持SpEL语法，用于生成缓存的唯一标识。
     *
     * <p>默认情况下，如果未指定key，则使用{@link org.springframework.cache.interceptor.SimpleKeyGenerator}生成键。
     * 可以通过实现{@link org.springframework.cache.interceptor.KeyGenerator}接口来自定义键生成策略。
     *
     * <p>SpEL表达式上下文可用变量：
     *
     * <ul>
     *   <li>{@code #root.method}: 目标方法对象
     *   <li>{@code #root.target}: 目标对象
     *   <li>{@code #root.args}: 方法参数数组
     *   <li>{@code #result}: 方法返回值（仅在{@code unless}表达式中可用）
     *   <li>方法参数名称或索引（如{@code #id}, {@code #p0}等）
     * </ul>
     *
     * @return 缓存键表达式
     * @see org.springframework.cache.interceptor.KeyGenerator
     * @see org.springframework.expression.spel.standard.SpelExpressionParser
     */
    @AliasFor(annotation = Cacheable.class)
    String key() default "";

    /**
     * 自定义键生成器Bean名称，与{@link #key()}互斥。
     *
     * <p>当需要更复杂的键生成逻辑时，可以通过实现{@link org.springframework.cache.interceptor.KeyGenerator}
     * 接口并注册为Spring Bean，然后在此处指定Bean名称来使用。
     *
     * @return 键生成器Bean名称，默认为空字符串表示使用默认键生成器
     * @see org.springframework.cache.interceptor.KeyGenerator
     * @see #key()
     * @see org.springframework.cache.interceptor.SimpleKeyGenerator
     */
    @AliasFor(annotation = Cacheable.class)
    String keyGenerator() default "";

    /**
     * 缓存管理器Bean名称，与{@link #cacheResolver()}互斥。
     *
     * <p>当需要指定特定的缓存管理器时使用，通常用于多缓存管理器场景。 如果未指定，则使用默认的缓存管理器。
     *
     * @return 缓存管理器Bean名称，默认为空字符串表示使用默认缓存管理器
     * @see org.springframework.cache.CacheManager
     * @see #cacheResolver()
     */
    @AliasFor(annotation = Cacheable.class)
    String cacheManager() default "";

    /**
     * 缓存解析器Bean名称，用于自定义缓存解析逻辑。
     *
     * <p>当需要更复杂的缓存解析逻辑时，可以通过实现{@link org.springframework.cache.interceptor.CacheResolver}
     * 接口并注册为Spring Bean，然后在此处指定Bean名称来使用。
     *
     * @return 缓存解析器Bean名称，默认为空字符串表示使用默认缓存解析器
     * @see org.springframework.cache.interceptor.CacheResolver
     * @see #cacheManager()
     */
    @AliasFor(annotation = Cacheable.class)
    String cacheResolver() default "";

    /**
     * 条件缓存表达式，仅在表达式结果为true时才缓存方法返回值。
     *
     * <p>该表达式在方法调用前求值，如果为{@code false}，则不会缓存方法返回值。 SpEL表达式可以使用以下上下文变量：
     *
     * <ul>
     *   <li>{@code #root.method}: 目标方法对象
     *   <li>{@code #root.target}: 目标对象
     *   <li>{@code #root.args}: 方法参数数组
     *   <li>方法参数名称或索引（如{@code #id}, {@code #p0}等）
     * </ul>
     *
     * <p>示例：
     *
     * <pre>{@code
     * // 只有当userId大于1000时才缓存
     * @RedisCacheable(value = "userCache", condition = "#userId > 1000")
     * public User getUserById(Long userId) { ... }
     * }</pre>
     *
     * @return 条件表达式，默认为空字符串表示始终缓存
     * @see #unless()
     * @see org.springframework.expression.spel.standard.SpelExpressionParser
     */
    @AliasFor(annotation = Cacheable.class)
    String condition() default "";

    /**
     * 条件不缓存表达式，在表达式结果为true时不缓存方法返回值。
     *
     * <p>与{@link #condition()}不同，此表达式在方法执行后求值，可以访问方法返回值。 如果表达式求值结果为{@code true}，则不会缓存方法返回值。
     * SpEL表达式可以使用以下上下文变量：
     *
     * <ul>
     *   <li>{@code #result}: 方法返回值
     *   <li>{@code #root.method}: 目标方法对象
     *   <li>{@code #root.target}: 目标对象
     *   <li>{@code #root.args}: 方法参数数组
     *   <li>方法参数名称或索引（如{@code #id}, {@code #p0}等）
     * </ul>
     *
     * <p>示例：
     *
     * <pre>{@code
     * // 不缓存返回null的结果
     * @RedisCacheable(value = "userCache", unless = "#result == null")
     * public User getUserById(Long userId) { ... }
     * }</pre>
     *
     * @return 条件表达式，默认为空字符串表示不应用任何条件
     * @see #condition()
     * @see org.springframework.expression.spel.standard.SpelExpressionParser
     */
    @AliasFor(annotation = Cacheable.class)
    String unless() default "";

    /**
     * 是否同步加载缓存，避免多线程重复计算相同缓存值。
     *
     * <p>当设置为{@code true}时，多个线程同时请求同一个缓存键时， 只有一个线程会执行方法体，其他线程会等待结果并返回相同的值。 这可以防止缓存击穿问题，但会降低并发性能。
     *
     * @return {@code true}表示同步加载缓存，{@code false}表示异步加载
     * @see #ttl()
     */
    @AliasFor(annotation = Cacheable.class)
    boolean sync() default false;

    /**
     * 缓存过期时间（秒），仅在支持TTL的缓存实现中生效，如Redis。
     *
     * <p>设置缓存条目的存活时间（Time To Live），超过指定时间后缓存条目将自动过期。 值为0表示永不过期（不推荐在生产环境中使用）。 负值将被视为使用缓存实现的默认过期时间。
     *
     * <p>注意：
     *
     * <ul>
     *   <li>不是所有的缓存实现都支持TTL功能
     *   <li>某些缓存实现可能有最大TTL限制
     *   <li>实际过期时间可能会有几秒的误差
     * </ul>
     *
     * @return 过期时间（秒），默认60秒
     * @see #sync()
     * @see org.springframework.data.redis.cache.RedisCacheConfiguration
     */
    long ttl() default 60;

    /**
     * 缓存值的类型，用于类型转换。
     *
     * <p>指定缓存值的类型，主要用于反序列化时进行类型转换。 当使用JSON序列化时，此属性可以帮助正确反序列化为目标类型。 如果未指定，则使用{@link Object}类型。
     *
     * <p>示例：
     *
     * <pre>{@code
     * // 指定返回类型为User
     * @RedisCacheable(value = "userCache", type = User.class)
     * public User getUserById(Long userId) { ... }
     * }</pre>
     *
     * @return 缓存值类型，默认为{@link Object}.class
     * @see com.fasterxml.jackson.databind.ObjectMapper
     * @see org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
     */
    Class<?> type() default Object.class;
}
