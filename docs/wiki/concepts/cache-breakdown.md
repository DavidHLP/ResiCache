---
title: 缓存击穿
type: concepts
tags: [缓存击穿, 防护概念]
related: [breakdown-lock, cache-penetration, cache-avalanche, hot-key]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/breakdown/SyncLockHandler.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 缓存击穿(Cache Breakdown)

## 定义

某个**热点 key** 在缓存中过期的**瞬间**,大量并发请求同时发现「未命中」,一齐回源重建缓存,瞬间压垮 DB。区别于 [[cache-avalanche|雪崩]](大批 key 同时过期),击穿是**单个** key 的高并发回源风暴。

## 关键特征

- 单 key、瞬间、高并发;
- 根因是「过期时刻」的回源竞争,而非 key 不存在(那是 [[cache-penetration|穿透]])。

## ResiCache 的解法:分布式锁

[[breakdown-lock]](链档 200)。`sync=true` 时,`SyncLockHandler` 用 Redisson 分布式锁**串行化回源**:

- 第一个未命中的请求拿到锁,回源重建缓存;
- 其余请求等锁,锁释放后直接读新写入的缓存——不再各自回源。

## 与提前过期的分工

[[early-expiration]](250)从另一个角度解决:让热 key **在过期之前**就异步刷新,使其几乎不出现「过期瞬间」。两者互补:

- 提前过期:**避免**热 key 真的过期(异步刷新,用户无感);
- 分布式锁:热 key **真的过期了**的兜底(串行化,防止风暴)。

高价值热 key 建议同时开 `enableEarlyExpiration`(主动)+ `sync`(兜底)。

## 配置

```java
@RedisCacheable(value = "hotdata", key = "#id",
    sync = true,                    // 击穿兜底:分布式锁
    enableEarlyExpiration = true,   // 主动:避免过期
    earlyExpirationMode = EarlyExpirationMode.ASYNC)
```

## 相关

- [[breakdown-lock]] —— 分布式锁实现细节
- [[hot-key]] —— 热点 key 概念(击穿的高危对象)
- [[cache-penetration]] / [[cache-avalanche]] —— 另两类缓存问题
