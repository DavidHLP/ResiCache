# ADR-0006: 定位为「ResiCache for Redisson — Redisson 忘了做的那条缓存防护链」

- **Status**: Accepted
- **Date**: 2026-06-28
- **Deciders**: DavidHLP
- **Related**: ADR-0001(取代其定位叙事)、ADR-0002、ADR-0005、`MASTER_PLAN.md`

## Context

ADR-0001 把 ResiCache 定位为「Spring Cache 防护增强注解生态」,差异化叙事为"防护纵深 + 可编排责任链 vs JetCache 的多级缓存"。该定位经多轮对抗式评审(战略评估 + 3 评委独立打分的定位评审团)后被裁定**叙事偏弱**:

- **JetCache 已覆盖 ResiCache 链中 4/5 机制**(`@CacheRefresh`≈EarlyExpiration、`@CachePenetrationProtect`≈SyncLock、TTL jitter、null-value),且额外提供 ResiCache 明确放弃的多级缓存、广播失效、API 级 `Cache.get/put`。ResiCache 唯一真实技术增量是 **Bloom 过滤器 + 可插拔责任链**,两者太薄,撑不起"独立防护库"对抗阿里背书 JetCache 的叙事。
- 缓存采用是**信任决策**。一个 solo 维护、v0.0.x、白名单默认作者包、序列化不兼容的库,在风险厌恶的企业里**对抗不了** JetCache,无论技术多好。

评审团(2:1,首席策略官复核同向加固)裁定转向 **Wedge 2「ResiCache for Redisson」**,并嫁接落选方案精华。

## Decision

重新定位为 **「ResiCache for Redisson — Redisson 忘了做的那条可声明缓存防护链」**:

- **核心叙事(信任算术)**: 不要求采用者"信任一个 solo 库作核心基建",而是要求他们**信任栈里已有的 Redisson**,加一层薄封装。向具名在位者 Redisson 提出可证伪问题——"Redisson 提供可声明的防护链吗?没有。"——这个空白是可感知的。
- **差异点(把护城河做实)**: 把散落的 `RLock`/`RBloomFilter`/手写 TTL,收敛成一条**可编排、可关断、可观测**的声明式防护链(一个 `@RedisCacheable` 顶三十个手写原语)。Bloom + 可插拔 `HandlerOrder` 链是 JetCache 结构上抄不走的(它要变成另一个项目)。
- **永远让给 JetCache**: 多级缓存、广播失效、API 级 `Cache.get/put`。**不正面竞争。**

### 嫁接(从落选 wedge 汲取)

- **来自 Wedge 1**: `@Cacheable` 锁定用户为**次要滩头**(`jetcache-core` 零 Spring 依赖 → 这些团队结构上用不了 JetCache);**Path C 重构必须做**(wedge 无关)。
- **来自 Wedge 3**: 内核无关化作 **ADR-0005 长寿对冲**,**不近期执行**(已验证 handlers 直依赖 `RedisTemplate`/`CacheStatisticsCollector`/`NullValue`,抽出需 3 端口,"便宜抽出"为伪命题);**链级 Micrometer Observation** 作企业可采购性解锁键。

## Consequences

- **正面**: 信任门槛从"信任 solo 核心基建"降到"信任 Redisson + 薄层";支持矩阵收窄到一家厂商节奏;项目最大负债(Redisson 耦合)转为引力源。
- **负面/风险**: 必须先发制人回答"为什么不直接调 `redissonClient.getBloomFilter()`/`getLock()`?"——靠 **before/after runnable sample**(定位的生存机制)+ 诚实对比页。链的编排价值只在"~30 个缓存用一套一致策略"时显现。
- **不变**: 嵌入机制(继承 `RedisCacheManager`)保留;Path C 后 `CacheInterceptor` 继承被替换(见 ADR-0002 修订)。
- **成功标尺**: ~50–200 star + 2–3 个已知生产用户(12 个月内)。不搞直接变现,只走 Sponsors / 咨询 on-ramp。

## 取代关系

本 ADR 取代 **ADR-0001** 的定位叙事(ADR-0001 保留为历史,但其叙事被本 ADR 覆盖)。完整战略与执行总纲见 `MASTER_PLAN.md`。
