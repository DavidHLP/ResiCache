package io.github.davidhlp.spring.cache.redis.operation;


import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Spring 原生 {@link Cacheable @Cacheable} 注解 → {@link RedisCacheableOperation} 的适配工厂。
 *
 * <p><strong>Spring 适配器</strong>。Spring 的 {@code @Cacheable} 字段名与
 * ResiCache 注解不同({@code value}/{@code cacheNames} 等价但缺 TTL/布隆/早过期等增强属性),
 * 且 Spring 注解<strong>不</strong>持有 3 处漂移字段({@code syncTimeout/expectedInsertions/
 * falseProbability}),所以适配器<em>不</em>走 {@link RedisCacheAttributesProjector} —— 直接用
 * Spring 注解字段构造 {@link RedisCacheAttributes},未指定字段由 POJO 的 {@code @Builder}
 * 默认值填入。
 *
 * <p>{@code toAttributes} 与 {@code materialize} 因承载 Spring→ResiCache 字段映射的非平凡逻辑,
 * 保留为命名 seam。
 */
@Component
public class SpringCacheableAdapterFactory
        implements OperationFactory<Cacheable, RedisCacheableOperation> {

    private final RedisCacheAttributesProjector projector;

    public SpringCacheableAdapterFactory(RedisCacheAttributesProjector projector) {
        this.projector = projector;
    }

    @Override
    public RedisCacheableOperation create(Method method, Cacheable annotation, String key) {
        RedisCacheAttributes a = toAttributes(annotation);
        return materialize(method, key, a);
    }

    /**
     * Spring {@code @Cacheable} → {@link RedisCacheAttributes} 的纯映射。
     * Spring 没有 ResiCache 增强字段(TTL/布隆/早过期/sync 等),保留 {@code @Builder} 默认。
     */
    RedisCacheAttributes toAttributes(Cacheable springCacheable) {
        // value 优先;为空则用 cacheNames(与 Spring 注解语义一致)
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
                // ——全部使用 @Builder 默认(ttl=0、null=false、bloom=false、sync=false 等)
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
        // Spring CacheableOperation.Builder 对 null/空字符串敏感({@code setKeyGenerator}
        // 会 assert notNull;{@code setCondition} 等也将空串视为"未设置")——只对 hasText 的字段赋值。
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
