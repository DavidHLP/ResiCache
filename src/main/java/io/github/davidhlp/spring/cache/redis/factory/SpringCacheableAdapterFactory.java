package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Spring 原生 {@link Cacheable @Cacheable} 注解 → {@link RedisCacheableOperation} 的适配工厂。
 *
 * <p><strong>Candidate C：Spring 适配器</strong>。Spring 的 {@code @Cacheable} 字段名与 ResiCache
 * 注解不同（{@code value} / {@code cacheNames} 等价但缺 TTL/布隆/早过期等增强属性），原本在
 * {@code CacheableAnnotationHandler.registerSpringCacheableOperation()} 内联 47 行
 * if-Builder 模板；现在收敛到本工厂的 {@code materialize(...)}，handler 不再持有 Build-if-Text 逻辑。
 *
 * <p>Spring 注解本身<strong>不</strong>持有 3 处漂移字段（{@code syncTimeout /
 * expectedInsertions / falseProbability}），所以适配器<em>不</em>走 {@link
 * RedisCacheAttributesProjector}——直接用 Spring 注解的字段构造 {@link RedisCacheAttributes}
 * 即可，未指定的字段由该 POJO 的 {@code @Builder} 默认值填入。
 *
 * <p>本工厂继承 {@link AbstractOperationFactory} 是为了在 {@code OperationFactory} 生态中
 * 与 {@code @RedisCacheable} 三个 factory 站同列——{@link OperationFactory#supports(Annotation)}
 * 自然识别 Spring {@code @Cacheable}（{@link #annotationClass()} 返回 Spring 类型）。
 */
@Component
public class SpringCacheableAdapterFactory
        extends AbstractOperationFactory<Cacheable, RedisCacheableOperation> {

    private final RedisCacheAttributesProjector projector;

    public SpringCacheableAdapterFactory(RedisCacheAttributesProjector projector) {
        this.projector = projector;
    }

    @Override
    public RedisCacheableOperation create(
            Method method, Cacheable annotation, Object target, Object[] args, String key) {
        RedisCacheAttributes a = toAttributes(annotation);
        return materialize(method, key, a);
    }

    /**
     * Spring {@code @Cacheable} → {@link RedisCacheAttributes} 的纯映射。
     * Spring 没有 ResiCache 增强字段（TTL/布隆/早过期/sync 等），保留 {@code @Builder} 默认。
     */
    RedisCacheAttributes toAttributes(Cacheable springCacheable) {
        // value 优先；为空则用 cacheNames（与 Spring 注解语义一致）
        String[] cacheNames = (springCacheable.value() != null && springCacheable.value().length > 0)
                ? springCacheable.value()
                : springCacheable.cacheNames();
        if (cacheNames == null) {
            cacheNames = new String[0];
        }

        return RedisCacheAttributes.builder()
                .cacheNames(cacheNames)
                .key(orEmpty(springCacheable.key()))
                .keyGenerator(orEmpty(springCacheable.keyGenerator()))
                .cacheManager(orEmpty(springCacheable.cacheManager()))
                .cacheResolver(orEmpty(springCacheable.cacheResolver()))
                .condition(orEmpty(springCacheable.condition()))
                .unless(orEmpty(springCacheable.unless()))
                // Spring @Cacheable 没有 TTL / null 缓存 / 布隆 / 早过期 / sync.lock 等增强字段
                // ——全部使用 @Builder 默认（ttl=0、null=false、bloom=false、sync=false 等）
                .sync(springCacheable.sync())
                // Evict-only 字段保持默认 false
                .allEntries(false)
                .beforeInvocation(false)
                .build();
    }

    /** 从 {@link RedisCacheAttributes} 构造 {@link RedisCacheableOperation}。 */
    RedisCacheableOperation materialize(Method method, String key, RedisCacheAttributes a) {
        RedisCacheableOperation.Builder builder = RedisCacheableOperation.builder()
                .name(method.getName())
                .key(key)
                .cacheNames(a.getCacheNames())
                .sync(a.isSync());
        // Spring CacheableOperation.Builder 对 null/空字符串敏感（{@code setKeyGenerator}
        // 会 assert notNull；{@code setCondition} 等也将空串视为"未设置"）——只对 hasText 的字段赋值。
        if (hasText(a.getKeyGenerator())) {
            builder.keyGenerator(a.getKeyGenerator());
        }
        if (hasText(a.getCacheManager())) {
            builder.cacheManager(a.getCacheManager());
        }
        if (hasText(a.getCacheResolver())) {
            builder.cacheResolver(a.getCacheResolver());
        }
        if (hasText(a.getCondition())) {
            builder.condition(a.getCondition());
        }
        if (hasText(a.getUnless())) {
            builder.unless(a.getUnless());
        }
        return builder.build();
    }

    @Override
    protected Class<Cacheable> annotationClass() {
        return Cacheable.class;
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }

    private static boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }

    // Visible for testing
    static String[] nonEmpty(String[] in) {
        return in == null ? new String[0] : Arrays.copyOf(in, in.length);
    }
}
