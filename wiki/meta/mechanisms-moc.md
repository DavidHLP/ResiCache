---
title: 机制拓扑 MOC
type: meta
tags:
  - meta
  - moc
  - 机制
  - 拓扑
  - 责任链
related: [chain-of-responsibility, cache-lifecycle, dashboard, modules-moc, overview]
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 机制拓扑 MOC(Map of Content)

> 5 大防护机制的责任链位置、配置开关、解决的问题。
> 用法:遇到机制相关问题,从这里定位 → 下钻到对应机制页 → 看源码实现。

![[meta/mechanisms-canvas.canvas]]

## 责任链上的顺序(单一真理源)

`HandlerOrder` 枚举,间隔 100,所有顺序问题以这个为准。

| 档位 | 机制 | handler | 关键动作 | 防什么 |
|---|---|---|---|---|
| **100** | [[bloom-filter\|布隆过滤器]] | `BloomFilterHandler` | PostProcess:bitmap 判定后才放行 | [[cache-penetration\|穿透]] |
| **200** | [[breakdown-lock\|分布式锁]] | `SyncLockHandler` | Redisson tryLock,锁内聚回源 | [[cache-breakdown\|击穿]] |
| **250** | [[early-expiration\|提前过期]] | `EarlyExpirationHandler` | 剩余 TTL < 阈值时异步刷新 | [[hot-key\|热 key]] / [[cache-avalanche\|雪崩]] |
| **300** | [[ttl-jitter\|TTL 抖动]] | `TtlHandler` | 写时加随机偏移,避免集中过期 | [[cache-avalanche\|雪崩]] |
| **400** | [[null-value\|空值缓存]] | `NullValueHandler` | 短 TTL 占位防反复回源 | [[cache-penetration\|穿透]] |
| **500** | `ActualCacheHandler` | 真正写 Redis | — |

> 详见 [[chain-of-responsibility]] 的「为什么是这个顺序」。

## 4 个缓存问题 ↔ 防御组合

> [!danger] 穿透([[cache-penetration]])
> key **从不存在**,缓存没有,DB 也没有。
> **ResiCache 双防线**:[[bloom-filter]] 在链首 100 档拦截 + [[null-value]] 在 400 档缓存 null 占位。

> [!warning] 击穿([[cache-breakdown]])
> **单个热 key** 过期瞬间,大量请求同时打到 DB。
> **ResiCache 防线**:[[breakdown-lock]] 200 档,Redisson 锁内聚,只让一个请求回源重建。

> [!warning] 雪崩([[cache-avalanche]])
> **大量 key 同时过期**,DB 承受瞬时洪峰。
> **ResiCache 双防线**:[[ttl-jitter]] 300 档打散过期时间 + [[early-expiration]] 250 档对热 key 提前异步刷新。

> [!tip] 热 key([[hot-key]])
> 访问频率极高的 key,常规 TTL 难以平衡「新鲜」与「击穿」。
> **ResiCache 防线**:[[early-expiration]] 250 档剩余 TTL 阈值触发异步刷新,[[breakdown-lock]] 兜底防刷新瞬间的击穿。

## 配置开关(汇总)

> [!example] `resi-cache.*` 与机制对应
> ```dataview
> TABLE tags, related
> FROM "mechanisms"
> SORT file.name ASC
> ```

> 详细配置见 [[configuration]] 与 [[configure-behavior]]。

## 动态视角(Dataview)

> [!info] 机制页当前状态
> ```dataview
> TABLE status, tags, file.cday AS "创建"
> FROM "mechanisms"
> SORT file.name ASC
> ```

## 下钻路径

- 想看源码:`source-files` 字段列出关键类,跳到 [[chain-of-responsibility]] 看整体
- 想加新机制:[[add-protection-handler]] 4 步流程
- 想理解控制流:[[handler-result-control]] CONTINUE/TERMINATE/SKIP_ALL 三态
