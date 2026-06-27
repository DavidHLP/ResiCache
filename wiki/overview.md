---
title: 项目概览
type: meta
tags:
  - meta
  - 概览
  - 入口
related: [index, chain-of-responsibility, auto-configuration, configuration, cache-lifecycle]
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# ResiCache 概览

> 这是 wiki 的阅读入口。从这里开始,顺着链接下钻。

## 一句话

ResiCache 是 **Spring Cache 的防护增强注解生态**——在 `@Cacheable` 之外,用 `@RedisCacheable` 一行注解 + 可编排的责任链,为 Redis 缓存补齐防穿透 / 防击穿 / 防雪崩 / 热 key 刷新能力。Spring Cache 只解决"缓存",ResiCache 解决"防护";`@Cacheable` 兼容回退但不获得防护,`@RedisCacheable` 即防护入口。

## 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| 语言 | Java | 17+ |
| 框架 | Spring Boot | 3.4.13 |
| 缓存 | Spring Cache + Spring Data Redis | — |
| 分布式锁 | Redisson | 3.27.0 |
| 本地缓存 / 位集 | Caffeine | 3.1.8 |
| 构建 | Maven | 3.x |
| 测试 | JUnit 5 + Testcontainers + AssertJ + Awaitility | — |

## 核心架构

所有缓存操作(GET / PUT / PUT_IF_ABSENT / REMOVE / CLEAN)流经一条责任链,顺序由 `HandlerOrder` 枚举单一真理源定义(间隔 100):

![[meta/overview.canvas]]

```
请求 → BloomFilter(100) → SyncLock(200) → EarlyExpiration(250) → TTL(300) → NullValue(400) → ActualCache(500)
```

→ 详见 [[chain-of-responsibility]]。完整读写路径见 [[cache-lifecycle]]。

## 五大防护机制 ↔ 缓存问题

| 机制 | 链档 | 防什么 |
|---|---|---|
| [[bloom-filter\|布隆过滤器]] | 100 | [[cache-penetration\|穿透]] |
| [[breakdown-lock\|分布式锁]] | 200 | [[cache-breakdown\|击穿]] |
| [[early-expiration\|提前过期]] | 250 | [[hot-key\|热 key]] / [[cache-avalanche\|雪崩]] |
| [[ttl-jitter\|TTL 抖动]] | 300 | [[cache-avalanche\|雪崩]] |
| [[null-value\|空值缓存]] | 400 | [[cache-penetration\|穿透]] |

## 阅读路线

**新人**:本页 → [[chain-of-responsibility]](骨架)→ [[cache-lifecycle]](血肉)→ 任一机制页(如 [[bloom-filter]])。

**想配置**: [[configuration]](全配置) → [[configure-behavior]](三层配置实操) → [[annotations]](注解属性)。

**想扩展**: [[add-protection-handler]](加新 handler)→ [[handler-result-control]](控制流)→ [[context-data-flow]](数据传递)。

**想理解装配**: [[auto-configuration]](Spring Boot starter 怎么生效)。

## 模块速查

cache 核心([[cache-core]])、注解([[annotations]])、操作([[operations]])、配置([[configuration]])、序列化([[serialization]])、可观测([[observability]])、淘汰([[eviction]])、元数据/装配辅助([[holder-and-config]])。

完整清单见 [[index]]。

## 重要说明:文档与代码已对齐

历史问题:`CLAUDE.md` / `README.md` 曾提及 `wrapper/`(熔断/限流)、`spi/`、`event/`、`evaluator/`、`CacheMetricsRecorder` 等,但它们在 `a5ab55b refactor: remove dead code` 后已从代码移除。**v0.0.3 文档诚实化已修正 README/CLAUDE.md**,并加入 CI 文档一致性护栏(`docs-link-check` job:黑名单防移除特性复发 + 白名单校验关键类存在),防止 stale facts 复发。本 wiki 始终以实际代码为准,详见 [[log]] 的 2026-06-27 条目。如需熔断/限流,由业务层配合 Resilience4j 实现(见 [[cache-avalanche]])。

## 相关

- [[index]] —— 全部页面目录
- [[chain-of-responsibility]] —— 架构脊柱
- [[auto-configuration]] —— 如何零配置生效
- [[configuration]] —— 配置项全表
- [[mechanisms-moc]] —— 机制拓扑 MOC
- [[modules-moc]] —— 模块依赖 MOC
- [[log]] —— wiki 维护历史
