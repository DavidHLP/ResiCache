package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;

import org.springframework.stereotype.Component;

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
 */
@Component
public class RedisCacheAttributesProjector {

    /**
     * 从 {@link RedisCacheable} 投影。
     * <p>注：{@code cacheNames} 与 {@code value} 合并——{@code cacheNames} 优先、为空则用 {@code value}。
     */
    public RedisCacheAttributes from(RedisCacheable annotation) {
        if (annotation == null) {
            return null;
        }
        return RedisCacheAttributes.builder()
                .cacheNames(resolveCacheNames(annotation.cacheNames(), annotation.value()))
                .key(annotation.key())
                .keyGenerator(annotation.keyGenerator())
                .cacheManager(annotation.cacheManager())
                .cacheResolver(annotation.cacheResolver())
                .condition(annotation.condition())
                .unless(annotation.unless())
                .ttl(annotation.ttl())
                .type(annotation.type())
                .cacheNullValues(annotation.cacheNullValues())
                .useBloomFilter(annotation.useBloomFilter())
                .expectedInsertions(annotation.expectedInsertions())
                .falseProbability(annotation.falseProbability())
                .randomTtl(annotation.randomTtl())
                .variance(annotation.variance())
                .enableEarlyExpiration(annotation.enableEarlyExpiration())
                .earlyExpirationThreshold(annotation.earlyExpirationThreshold())
                .earlyExpirationMode(annotation.earlyExpirationMode())
                .sync(annotation.sync())
                .syncTimeout(annotation.syncTimeout())
                // Evict-only 字段：Cacheable 不适用，保持默认 false
                .allEntries(false)
                .beforeInvocation(false)
                .build();
    }

    /**
     * 从 {@link RedisCachePut} 投影。
     */
    public RedisCacheAttributes from(RedisCachePut annotation) {
        if (annotation == null) {
            return null;
        }
        return RedisCacheAttributes.builder()
                .cacheNames(resolveCacheNames(annotation.cacheNames(), annotation.value()))
                .key(annotation.key())
                .keyGenerator(annotation.keyGenerator())
                .cacheManager(annotation.cacheManager())
                .cacheResolver(annotation.cacheResolver())
                .condition(annotation.condition())
                .unless(annotation.unless())
                .ttl(annotation.ttl())
                .type(annotation.type())
                .cacheNullValues(annotation.cacheNullValues())
                .useBloomFilter(annotation.useBloomFilter())
                .expectedInsertions(annotation.expectedInsertions())
                .falseProbability(annotation.falseProbability())
                .randomTtl(annotation.randomTtl())
                .variance(annotation.variance())
                .enableEarlyExpiration(annotation.enableEarlyExpiration())
                .earlyExpirationThreshold(annotation.earlyExpirationThreshold())
                .earlyExpirationMode(annotation.earlyExpirationMode())
                .sync(annotation.sync())
                .syncTimeout(annotation.syncTimeout())
                // Evict-only 字段：Put 不适用，保持默认 false
                .allEntries(false)
                .beforeInvocation(false)
                .build();
    }

    /**
     * 从 {@link RedisCacheEvict} 投影。
     * <p>{@code unless} 在 Evict 注解中存在，但 Evict 的 Builder 没有 {@code unless} 槽位——
     * 这里保留字段（语义一致），由 {@code EvictOperationFactory} 自行决定是否应用。
     */
    public RedisCacheAttributes from(RedisCacheEvict annotation) {
        if (annotation == null) {
            return null;
        }
        return RedisCacheAttributes.builder()
                .cacheNames(resolveCacheNames(annotation.cacheNames(), annotation.value()))
                .key(annotation.key())
                .keyGenerator(annotation.keyGenerator())
                .cacheManager(annotation.cacheManager())
                .cacheResolver(annotation.cacheResolver())
                .condition(annotation.condition())
                .unless(annotation.unless())
                .ttl(annotation.ttl())                // Evict: 0 = 不设置过期
                .type(Object.class)                  // Evict 注解无 type 字段
                .cacheNullValues(false)              // Evict 注解无 cacheNullValues 字段
                .useBloomFilter(annotation.useBloomFilter())
                .expectedInsertions(annotation.expectedInsertions())
                .falseProbability(annotation.falseProbability())
                .randomTtl(false)                    // Evict 注解无 randomTtl 字段
                .variance(0.0F)                      // Evict 注解无 variance 字段
                .enableEarlyExpiration(annotation.enableEarlyExpiration())
                .earlyExpirationThreshold(annotation.earlyExpirationThreshold())
                .earlyExpirationMode(annotation.earlyExpirationMode())
                .sync(annotation.sync())
                .syncTimeout(annotation.syncTimeout())
                .allEntries(annotation.allEntries())
                .beforeInvocation(annotation.beforeInvocation())
                .build();
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
