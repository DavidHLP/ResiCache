/**
 * Spring Cache 集成核心 — 入口与 package-private runtime collaborators。
 *
 * <p>本包承载 Spring Cache 入口与内部协作者:
 * <ul>
 *   <li>{@link io.github.davidhlp.spring.cache.redis.cache.RedisProCacheManager} — Spring {@code CacheManager}</li>
 *   <li>{@link io.github.davidhlp.spring.cache.redis.cache.RedisProCache} — Spring {@code RedisCache}</li>
 * </ul>
 *
 * <p>All implementation collaborators are intentionally package-private and
 * assembled by the package configuration. Stable extension contracts remain
 * in {@code chain}, {@code chain.model}, and the documented protection seams.
 */
package io.github.davidhlp.spring.cache.redis.cache;

