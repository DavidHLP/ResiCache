---
title: 缓存穿透
type: concepts
tags:
  - concept
  - 缓存穿透
  - 防护概念
related: [bloom-filter, null-value, cache-breakdown, cache-avalanche]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/bloom/BloomFilterHandler.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/nullvalue/NullValueHandler.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 缓存穿透(Cache Penetration)

## 定义

查询一个**根本不存在**的 key,缓存和数据库都没有。每次请求都穿过缓存直达 DB。若被恶意利用(用大量随机不存在的 key 轰炸),可压垮数据库。

## 与同类问题区分

| 问题 | 触发 | ResiCache 机制 |
|---|---|---|
| **穿透**(本页) | key 从不存在 | [[bloom-filter]] + [[null-value]] |
| [[cache-breakdown\|击穿]] | 单个热 key 过期瞬间 | [[breakdown-lock]] |
| [[cache-avalanche\|雪崩]] | 大量 key 同时过期 | [[ttl-jitter]] + [[early-expiration]] |

## ResiCache 的两道防线

### 1. 布隆过滤器([[bloom-filter]],链档 100)

查 Redis 前,先问布隆「这个 key 可能存在吗?」。布隆判定「不在」是 100% 准确的——直接返回 miss,根本不查 Redis、不回源。

- 优势:零 Redis 开销,挡掉绝大多数非法 key;
- 局限:有假阳性(判「在」其实不在,需回源);重启/清空后需重新填充。

### 2. 空值缓存([[null-value]],链档 400)

对布隆漏网(假阳性或未覆盖)的 key,真去查 DB 得到 `null` 后,把这个「null」短 TTL 缓存。下次同 key 直接命中空值占位,返回 null,不再回源。

## 何时用哪个

| 场景 | 推荐 |
|---|---|
| key 空间大、攻击面广(如按 ID 查询,ID 可被枚举) | 布隆为主 |
| 业务上确实会有「查不到」的正常查询,且会反复查同一个 | 空值缓存为主 |
| 高安全 + 高可用 | 两者都开(布隆挡大头,空值兜漏网) |

实战通常**组合使用**:布隆是第一道(挡从未存在),空值缓存是第二道(兜住查过但确实没有的)。两者在责任链里依次执行(100 → 400)。

## 配置

```java
@RedisCacheable(value = "users", key = "#id",
    useBloomFilter = true,       // 第一道
    cacheNullValues = true)      // 第二道
```

## 相关

- [[bloom-filter]] —— 第一道防线
- [[null-value]] —— 第二道防线
- [[cache-breakdown]] / [[cache-avalanche]] —— 另两类缓存问题
