/**
 * Cache 域值对象 — 区分于 {@link io.github.davidhlp.spring.cache.redis.cache} 根包内的 Spring 适配器
 * (Interceptor / Manager / Writer / Cache) 与 {@link io.github.davidhlp.spring.cache.redis.cache.loader}
 * 内的编排 seam (LoaderOrchestrator / CacheOperationResolver)。
 *
 * <p>本包仅承载纯数据载体:
 * <ul>
 *   <li>{@link io.github.davidhlp.spring.cache.redis.cache.model.CacheKeys} — key 派生 record</li>
 *   <li>{@link io.github.davidhlp.spring.cache.redis.cache.model.CachedValue} — cache 负载值对象(含 TTL/Expiry 算术)</li>
 *   <li>{@link io.github.davidhlp.spring.cache.redis.cache.model.ResiCacheFeatures} — 可选特性集合值对象</li>
 * </ul>
 *
 * <p>区分理由:此前 {@code cache/} 是扁平包,把 Spring-extension 类型(RedisCacheInterceptor 等)
 * 与框架内部值对象混在一起,新成员无明确归属指引(原 package-info 为空)。本子包沿用
 * {@code chain/} 已建立的「root + handler/ + model/ + observer/」子包纪律,让 cache/ 树同构。
 */
package io.github.davidhlp.spring.cache.redis.cache.model;
