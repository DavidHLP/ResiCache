/**
 * Cache 域编排 seam — 协调 Spring Cache 调用与 ResiCache 内部协议的薄层。
 *
 * <p>本包承载从 {@code cache/} 根包抽出的 2 个编排 seam(均为 ADR-0062 / Wave-1 候选提取):
 * <ul>
 *   <li>{@link io.github.davidhlp.spring.cache.redis.cache.loader.LoaderOrchestrator} —
 *       Spring {@code Cache.get(key, loader)} 路径的 3-step 编排(bloom 短路 → sync 锁 → 默认本地锁),
 *       封装 {@code LoadOutcome} 三态结果。从 {@code RedisProCache} 抽出(ADR-0062)。</li>
 *   <li>{@link io.github.davidhlp.spring.cache.redis.cache.loader.CacheOperationResolver} —
 *       method → operation 元数据查找 seam,消除 {@code RedisProCache} 与 {@code RedisProCacheWriter}
 *       之间的 4-line 镜像 lookup 样板。</li>
 * </ul>
 *
 * <p>区分理由:这 2 个类既不是值对象( {@link io.github.davidhlp.spring.cache.redis.cache.model} )
 * 也不是 Spring 适配器(根包),它们是协调外部 Spring Cache 抽象与内部 chain/handler/protection
 * 协议的中间层。独立子包让 3 种角色(Spring 适配器 / 值对象 / 编排 seam)的归属一次性清晰。
 */
package io.github.davidhlp.spring.cache.redis.cache.loader;
