---
title: 热点 Key
category: concepts
tags: [热点key, hot-key, 防护概念]
related: [early-expiration, cache-breakdown, cache-avalanche]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/refresh/EarlyExpirationHandler.java
updated: 2026-06-21
---

# 热点 Key(Hot Key)

## 定义

某个 key 的访问量远高于其他 key(如爆款商品、热门话题)。问题有二:

1. **过期瞬间**引发 [[cache-breakdown|击穿]]——高并发回源风暴;
2. **持续高流量**可能压垮 Redis 单分片(单 key 只落在单个槽/节点)。

## 与击穿的区别

- **击穿**是「过期时刻」的瞬时问题,任何 key 都可能发生;
- **热 key** 是「持续高访问量」的状态,是击穿的高危对象。热 key 一旦过期,击穿后果更严重。

## ResiCache 的应对:提前过期

[[early-expiration]](链档 250)。让热 key 在剩余 TTL 低于阈值(默认 30%)时**异步刷新**,使其几乎不进入过期状态——用户永远拿到缓存,不触发回源风暴。

```java
@RedisCacheable(value = "trending", key = "#id",
    enableEarlyExpiration = true,
    earlyExpirationThreshold = 0.3,              // 剩余 30% 触发
    earlyExpirationMode = EarlyExpirationMode.ASYNC)  // 后台刷新,用户无感
```

异步模式的并发安全由 Lua CAS 脚本保证(只让一个请求真正刷新),见 [[early-expiration]]。

## 如何识别热 key

框架内 `TwoListLRU`([[eviction]])的 active/inactive 双链表可辅助识别高频访问的 key(active 链表即「热」集)。`EarlyExpirationSupport.getRefreshingKeyCount()` / `getThreadPoolStats()` 可观测刷新压力。

## 局限

提前过期解决「过期风暴」,但不解决「单分片流量集中」(那需 key 拆分/多副本/本地缓存)。当前版本框架未内置本地副本层——可由使用方加 Caffeine 本地缓存兜底。

## 相关

- [[early-expiration]] —— 核心应对机制
- [[cache-breakdown]] —— 热 key 过期的后果
- [[eviction]] —— 双链表可辅助识别热度
