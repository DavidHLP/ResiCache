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

1. **引入 `resi-cache.protection.preset = STRICT / STANDARD / NONE`**(v0.2.0):
   - `STANDARD`:开启安全默认(布隆 + 空值 + TTL 抖动,不含 sync);
   - `STRICT`:全开(含 sync,要求 Redisson);
   - `NONE`:全关(等价 `protection.enabled=false`)。
2. **注解级默认仍 `false`**——preset 只是把"逐个开"变成"一键套餐",不改变显式语义。
3. `shouldHandle` 在 `cacheOperation == null`(如全局 preset 无注解覆盖)时回退全局配置。
4. v0.0.3 先落地 `resi-cache.protection.enabled` 总开关(已实现,
   见 `CacheHandlerChainFactory#createChain` 短路逻辑)。

## Consequences

- **正面**:用户一键获得安全默认套餐,同时保留注解级覆盖;避免"默认全开"把未验证防护
  强加用户。
- **负面**:v0.2.0 前,用户仍需逐个开启(README 已明示,v0.0.3 仅有总开关)。
- **不变**:任何防护开启都需用户显式选择(preset 或注解),框架不替用户做隐性决策。
