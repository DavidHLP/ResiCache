---
title: 缓存雪崩
type: concepts
tags: [缓存雪崩, 防护概念]
related: [ttl-jitter, early-expiration, cache-penetration, cache-breakdown]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/avalanche/TtlHandler.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/refresh/EarlyExpirationHandler.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 缓存雪崩(Cache Avalanche)

## 定义

**大量 key 在同一时刻过期**(或 Redis 整体宕机),请求全部回源,DB 瞬间过载崩溃。常见诱因:批量预热的缓存用了相同 TTL,到期时集体失效。

## 两种形态

| 形态 | 根因 | ResiCache 应对 |
|---|---|---|
| **同时过期型**(本页主) | 大批 key 相同 TTL,同时失效 | [[ttl-jitter]] + [[early-expiration]] |
| **Redis 宕机型** | 缓存层整体不可用 | 框架未直接提供(需本地兜底/限流降级,见下) |

## 解法一:TTL 抖动([[ttl-jitter]],链档 300)

给每个 key 的 TTL 加随机偏移(`randomTtl=true`, `variance=0.2` → ±20% 高斯抖动)。原本「同时写入、同时过期」的 key 群,过期时间被打散在一段时间窗内,DB 压力平滑。

- 适合:批量预热、同类数据;
- 原理见 [[ttl-jitter]] 的 `DefaultTtlPolicy.calculateFinalTtl`。

## 解法二:提前过期([[early-expiration]],链档 250)

对热点 key,在**真正过期之前**就异步刷新,让它几乎不进入「过期」状态——从源头消除过期瞬间的回源压力。

- 适合:可识别的热 key;
- 原理见 [[early-expiration]] 的阈值判定 + 异步线程池。

## 两者配合

抖动解决「同时过期」,提前过期解决「热点过期」。防雪崩的典型组合:

```java
@RedisCacheable(value = "products", key = "#id",
    randomTtl = true, variance = 0.2f,          // 打散过期时间
    enableEarlyExpiration = true,                // 热点主动刷新
    earlyExpirationMode = EarlyExpirationMode.ASYNC)
```

## Redis 宕机型雪崩

ResiCache 聚焦于「同时过期型」。Redis 整体宕机的雪崩需要:**熔断/降级**(防止应用被拖死)、**本地兜底缓存**(Caffeine 本地层)、**多级缓存**。这些属于应用级韧性设计,当前版本框架未内置独立熔断组件(`wrapper/` 已在重构中移除,见 [[log]])——可由使用方在业务层配合 Resilience4j 等实现。

## 相关

- [[ttl-jitter]] —— 被动打散
- [[early-expiration]] —— 主动刷新
- [[cache-penetration]] / [[cache-breakdown]] —— 另两类缓存问题
