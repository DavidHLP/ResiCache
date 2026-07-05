# ADR-0001: 定位为「Spring Cache 防护增强注解生态」

- **Status**: Accepted
- **Date**: 2026-06-27
- **Deciders**: DavidHLP
- **Related**: Q1（定位）, README 重写

## Context

早期定位是"基于 Spring Cache 和 Redis 的高性能缓存防护工具包"(见历史 pom
description)。这带来两个问题:

1. **"高性能"无 benchmark 支撑**——v0.0.2 无 JMH 数据,属虚假宣传。
2. **"嵌入 Spring Cache Redis" 的定位模糊**:与 Spring Cache 原生、JetCache 的
   关系不清,潜在用户不知道为何选 ResiCache。

嵌入机制本身(继承 `RedisCacheManager` / `CacheInterceptor`,双 Advisor)是合理的
——它让 ResiCache 与 `@EnableCaching` 共存、零迁移。问题在"定位叙事",不是技术方向。

## Decision

重新定位为 **「Spring Cache 的防护增强注解生态」**,核心叙事:

- **Spring Cache 只解决"缓存",不解决"防护"**——穿透/击穿/雪崩/热 key 是 ResiCache 的领地。
- **`@Cacheable` 兼容回退,但不获得防护**;`@RedisCacheable` 是防护入口。
- **与 JetCache 差异化**:JetCache = 多级缓存;ResiCache = 防护纵深 + 可编排责任链。
- 删除"高性能"措辞;只有 JMH 数据可用时方能据实陈述性能。

## Consequences

- **正面**:目标用户(已有 Spring Cache + Redis、缺防护能力的团队)清晰;与 JetCache
  互补而非竞争;推广叙事聚焦"补齐防护"。
- **负面**:用户必须主动从 `@Cacheable` 迁移到 `@RedisCacheable` 才有防护——这是
  有意识的迁移,不是无感增强。需在 README 明确(已做)。
- **不变**:嵌入机制(继承而非替换)继续保留——这是"与 Spring Cache 共存"的技术基础。
