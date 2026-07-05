# ADR-0005: 内核无关化仅作长寿对冲,不近期执行

- **Status**: Accepted
- **Date**: 2026-06-28
- **Deciders**: DavidHLP
- **Related**: ADR-0006、[[overview]](战略总纲)、[[0002-keep-interceptor]](Path C;原 `MASTER_PLAN.md` 已归档 commit `0bc6c2b`)

## Context

定位评审中,Wedge 3「把责任链 + 5 机制抽成零 Spring 依赖的 framework-agnostic 内核 + 多 adapter(Spring Cache / JetCache / standalone)」被否决为近期方向,但保留了"内核无关化"的**长寿对冲**价值:若 Spring/SDR 4 的 churn 只命中 adapter 层,内核可幸存。

是否近期执行该抽取,取决于**当前 handlers 对 Spring 的实际耦合度**。

## Decision

**不近期做内核抽取**,仅作为记录在案的长寿对冲。理由(经代码核验):

handlers 的 `shouldHandle`/`doHandle` 虽然不 import `Cache`/`ValueWrapper` SPI,但**直接依赖**三个 Spring Data Redis / Spring 类型:
- `org.springframework.data.redis.core.RedisTemplate`(ActualCacheHandler / EarlyExpirationHandler 读写)
- `CacheStatisticsCollector`(`NullValue`/统计路径)
- `NullValue`(SDR 的空值类型)

→ 抽出 framework-agnostic 内核需要定义 **3 个端口接口**(`KeyValueStore`/`StatisticsCollector`/`NullValueRepresentation`),**不是 1 个**。Wedge 3"便宜抽出"的前提**经核验为假**。对 solo 维护者,这意味着付出多 adapter 测试矩阵的税,却只换得"未来可能少改一个 adapter"的远期收益——**EV 为负**。

## Consequences

- **近期**: 保持单 artifact、handlers 直依赖 SDR 类型;Spring/SDR churn 由 Path C(降低 subclass 面)与双构建矩阵(WS-1.1)对冲,**不**靠内核抽取。
- **长寿对冲(保留)**: 在内部维持"handlers 不碰 `Cache`/`ValueWrapper` SPI"的纪律,使**未来若**真要抽取时,只需补 3 个端口而非重构全链。README 可据此声称"内核指向可移植性,只有 adapter 受 Spring EOL churn 影响"——作为结构性论据,不作为近期承诺。
- **触发重评估条件**: 若 JetCache 出可插拔链(迫使差异点上移到"framework-agnostic 内核"),或 Spring 7/Boot 4 churn 证明 adapter 维护成本不可承受,则重新评估本 ADR。
