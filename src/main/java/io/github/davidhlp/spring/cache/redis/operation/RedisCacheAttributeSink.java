package io.github.davidhlp.spring.cache.redis.operation;

import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;

/**
 * The 14 cache-attribute setters shared by all three ResiCache Operation builders —
 * the single source of truth for the {@code RedisCacheAttributes} → Builder field mapping.
 *
 * <p><b>This interface is the real single source</b>: {@link RedisCacheAttributes} holds
 * ONE {@code COMMON_SINKS} constant typed against this interface, and the three builders realize
 * it. A missing or renamed setter on any builder is a <b>compile error</b>, not a silent drift.
 * Adding a common field is 2 touch points (one interface method + one COMMON_SINKS entry).
 *
 * <p><b>Covariant returns</b>: each interface method returns {@code RedisCacheAttributeSink}.
 * The concrete builders' chainable setters already return their own {@code Builder} type, which
 * satisfies the interface via Java covariant returns — so {@code implements RedisCacheAttributeSink}
 * requires zero setter-body changes.
 *
 * <p><b>Three real adapters</b> (a genuine seam, not a hypothetical one — "one adapter = a
 * hypothetical seam, two = a real one"): {@link RedisCacheableOperation.Builder},
 * {@link RedisCachePutOperation.Builder}, {@link RedisCacheEvictOperation.Builder}. All three
 * extend different Spring parents ({@code CacheableOperation.Builder} /
 * {@code CachePutOperation.Builder} / {@code CacheEvictOperation.Builder}) and therefore share
 * no ResiCache-specific setter supertype without this interface.
 *
 * <p><b>Deletion test</b>: removing this interface re-triplicates the sink list and restores the
 * drift hazard. It earns its keep.
 *
 * @see RedisCacheAttributes#applyTo(RedisCacheableOperation.Builder)
 * @see AttributePopulator
 */
public interface RedisCacheAttributeSink {

    RedisCacheAttributeSink cacheNames(String... cacheNames);

    RedisCacheAttributeSink keyGenerator(String keyGenerator);

    RedisCacheAttributeSink cacheManager(String cacheManager);

    RedisCacheAttributeSink cacheResolver(String cacheResolver);

    RedisCacheAttributeSink condition(String condition);

    RedisCacheAttributeSink sync(boolean sync);

    RedisCacheAttributeSink syncTimeout(long syncTimeout);

    RedisCacheAttributeSink ttl(long ttl);

    RedisCacheAttributeSink useBloomFilter(boolean useBloomFilter);

    RedisCacheAttributeSink expectedInsertions(long expectedInsertions);

    RedisCacheAttributeSink falseProbability(double falseProbability);

    RedisCacheAttributeSink enableEarlyExpiration(boolean enableEarlyExpiration);

    RedisCacheAttributeSink earlyExpirationThreshold(double earlyExpirationThreshold);

    RedisCacheAttributeSink earlyExpirationMode(EarlyExpirationMode earlyExpirationMode);
}
