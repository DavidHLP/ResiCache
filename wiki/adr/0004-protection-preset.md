# ADR-0004: 用 protection.preset 批量启用,而非默认全开

- **Status**: Accepted
- **Date**: 2026-06-27
- **Deciders**: DavidHLP
- **Related**: Q6（防护默认值）

## Context

v0.0.2 中 `@RedisCacheable` 的 5 大防护属性(`useBloomFilter`/`cacheNullValues`/
`randomTtl`/`enableEarlyExpiration`/`sync`)**默认全 `false`**,用户须逐个显式开启。
体验差,易漏开。

但"默认全开 true"有隐性风险:

- `sync=true` 防击穿依赖 **Redisson**(见 `DistributedLockManager` 类的
  `@ConditionalOnClass(RedissonClient.class)`)。Redisson 缺失时 `SyncSupport`
  (`SyncSupport` 类)降级为 JVM 内锁——**跨实例失效**。默认开 sync 会让没装
  Redisson 的用户"以为有分布式锁,实则裸奔"。
- 布隆/TTL 抖动等有参数(`expectedInsertions`/`variance`),默认值不一定适合所有场景。

即:**默认全开 = 把未经验证的防护强加给用户**,违背"防护应显式、可观测"原则。

## Decision

1. **`resi-cache.protection.enabled` 总开关已落地**(短路 `CacheHandlerChainFactory#createChain`,
   关闭时跳过 protection handlers 但保留 TTL 计算)。`protection.preset` 三个预设
   `STRICT / STANDARD / NONE` 的设计已记录在本 ADR,实现延后 — pre-1.0 阶段仅暴露
   总开关。
2. **注解级默认仍 `false`**——任何机制的开启都必须显式表达。
3. `shouldHandle` 在 `cacheOperation == null` 时回退全局配置,作为未来 preset 实现的
   留位点。

## Consequences

- **正面**:总开关提供 coarse-grained 关闭路径;注解级显式语义保留,避免"默认全开"
  把未验证防护强加给用户。
- **负面**:pre-1.0 阶段用户仍需逐个开启(README 已明示,仅有总开关 + 注解级开关)。
- **不变**:任何防护开启都需用户显式选择(总开关或注解),框架不替用户做隐性决策。
