---
title: 淘汰策略(eviction)
type: modules
tags:
  - module
  - TwoListLRU
  - 双链表
  - 近似LRU
  - 淘汰
related: [cache-core, cache-lifecycle]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/eviction/TwoListLRU.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/eviction/TwoListEvictionStrategy.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/eviction/EvictionStrategy.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/eviction/EvictionStats.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 淘汰策略(`eviction` 包)

本地双链表近似 LRU 实现,灵感类似 Redis 4.0 `allkeys-lfu` 的「分段采样」思想——用两个链表区分「活跃」与「不活跃」,在有限内存里逼近真实 LRU 的命中率,而无需维护严格的完整有序结构。

## TwoListLRU

`src/main/java/io/github/davidhlp/spring/cache/redis/eviction/TwoListLRU.java:14`

泛型 `TwoListLRU<K, V>`,核心是两条链表:

| 链表 | 默认容量 | 角色 |
|---|---|---|
| **Active**(活跃) | `DEFAULT_MAX_ACTIVE_SIZE = 1024` | 被多次访问的「热」元素 |
| **Inactive**(试用) | `DEFAULT_MAX_INACTIVE_SIZE = 512` | 新进入、只访问过一两次的「候选」元素 |

## 晋升与淘汰(算法)

```
访问 K:
  if K 不在任何链表:
      K 加入 Inactive 尾部
  elif K 在 Inactive:
      K 晋升到 Active 头部
  elif K 在 Active:
      K 移到 Active 头部
  if Active.size > maxActiveSize:
      淘汰 Active 尾部 K_old → 触发 EvictionCallback(K_old)
  if Inactive.size > maxInactiveSize:
      淘汰 Inactive 尾部 K_old → 触发 EvictionCallback(K_old)
```

- **新元素**进 Inactive;
- **Inactive 元素被再次访问** → 晋升 Active(「二次访问」才认定有价值,过滤一次性扫描噪声);
- **Active 满**时,尾部(最久未活跃)被淘汰;
- **Inactive 满**时,尾部淘汰。

这是 S4LRU / 2Q 类算法的简化:用「晋升门槛」抵抗扫描污染(scanning resistance)——一次性扫描大量唯一 key 不会把热数据挤出 Active。

## 线程安全与回调

- `ReentrantReadWriteLock` + `ConcurrentHashMap` 保证并发安全(读锁并发查、写锁独占写);
- `EvictionCallback<K,V>` 内部接口(`TwoListLRU.java:534`)——元素被淘汰时回调,可联动清理资源、发事件;
- `evictionPredicate`(`Predicate<V>`)——自定义淘汰谓词,决定哪些值可被淘汰(返回 false 跳过本次淘汰);
- `totalEvictions`(`AtomicLong`)——累计淘汰计数(线程安全);
- `validateConsistency()` —— 仅测试 / 调试用,校验内部结构一致性。

## TwoListEvictionStrategy

`src/main/java/io/github/davidhlp/spring/cache/redis/eviction/TwoListEvictionStrategy.java:9`

```java
public class TwoListEvictionStrategy<K, V> implements EvictionStrategy<K, V>
```

把 `TwoListLRU` 包装成标准淘汰策略接口,持有 active / inactive 容量上限,对外暴露统一的淘汰 API。`EvictionStats` 承载统计快照(各链表大小、淘汰次数)供观测。

## 适用场景

这是 **JVM 本地**的近似 LRU,主要用于:

- 本地辅助数据结构(如 [[bloom-filter]] 的 `LocalBloomIFilter` 内部位集之外的本地缓存、临时结果聚合)的容量管控;
- 需要按访问热度筛选热 key 的辅助场景(配合 [[early-expiration|早期过期]] 的热 key 识别)。

**不是** Redis 端的淘汰——Redis 侧的过期由 [[ttl-jitter|TTL 抖动]] 决定,真正的 LRU / LFU 由 Redis 自身策略(`maxmemory-policy`)负责。本包提供的是框架内可选的**本地近似 LRU 工具**,与 Redis 端淘汰解耦。

## 与 Redis 端淘汰的对比

| 维度 | 本包 `TwoListLRU` | Redis `maxmemory-policy=allkeys-lru` |
|---|---|---|
| 范围 | JVM 堆内 | Redis 内存 |
| 精度 | 近似(两段采样) | 真 LRU(大开销) / 近似(小开销) |
| 跨实例 | 否(本地) | 是(各节点独立) |
| 适用 | 本地辅助结构 | 缓存主存储 |

两者互补:Redis 端控制缓存总容量,本包控制本地辅助结构的内存。

## 使用示例(伪代码)

```java
TwoListLRU<String, Bitmap> localFilters = new TwoListLRU<>(1024, 512);
localFilters.setEvictionCallback((key, bitmap) -> {
    log.debug("Local LRU evicted: {}", key);
    // 可选:发事件、清理关联资源
});
localFilters.add("users:bloom", bitmap);
boolean hot = localFilters.touch("users:bloom");   // 标记访问;可能晋升
EvictionStats stats = localFilters.stats();      // 观测
```

## 相关

- [[cache-core]] —— 本地数据结构所在的 cache 层
- [[cache-lifecycle]] —— Redis 侧过期由 TTL handler 控制,与本地淘汰区分
- [[early-expiration]] —— 双链表可辅助识别热 key
