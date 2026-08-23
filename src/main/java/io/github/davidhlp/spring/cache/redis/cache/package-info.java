/**
 * Spring Cache 集成核心 — Spring 适配器层(Interceptor / Manager / Writer / Cache)。
 *
 * <p>本根包只承载直接扩展 Spring Cache 抽象的类型:
 * <ul>
 *   <li>{@link io.github.davidhlp.spring.cache.redis.cache.RedisCacheInterceptor} — Spring AOP advice</li>
 *   <li>{@link io.github.davidhlp.spring.cache.redis.cache.RedisProCacheManager} — Spring {@code CacheManager}</li>
 *   <li>{@link io.github.davidhlp.spring.cache.redis.cache.RedisProCacheWriter} — SDR {@code RedisCacheWriter}</li>
 *   <li>{@link io.github.davidhlp.spring.cache.redis.cache.RedisProCache} — Spring {@code RedisCache}</li>
 * </ul>
 *
 * <p>子包:
 * <ul>
 *   <li>{@link io.github.davidhlp.spring.cache.redis.cache.model} — 纯数据载体(CacheKeys / CachedValue / ResiCacheFeatures)</li>
 *   <li>{@link io.github.davidhlp.spring.cache.redis.cache.loader} — 编排 seam(LoaderOrchestrator / CacheOperationResolver)</li>
 *   <li>{@link io.github.davidhlp.spring.cache.redis.cache.metrics} — Micrometer 指标记录(CacheMetrics / RedisProCacheMetricsRegistry / RedisProCacheTimers)</li>
 * </ul>
 *
 * <p>子包纪律与 {@code chain/} 同构(root + handler/ + model/ + observer/ + metadata/)。
 */
package io.github.davidhlp.spring.cache.redis.cache;

