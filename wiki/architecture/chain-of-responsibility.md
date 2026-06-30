---
title: 责任链架构
type: architecture
tags:
  - architecture
  - 责任链
  - handler
  - HandlerOrder
  - 核心架构
related: [cache-lifecycle, context-data-flow, handler-result-control, auto-configuration, add-protection-handler, observability]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/HandlerOrder.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/AbstractCacheHandler.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/CacheHandlerChain.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/CacheHandlerChainFactory.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/ChainEngine.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/ChainObserver.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/observer/
status: stable
created: 2026-06-21
updated: 2026-07-01
---

# 责任链架构(Chain of Responsibility)

ResiCache 的每一项缓存操作(GET / PUT / PUT_IF_ABSENT / REMOVE / CLEAN)都流经一条**责任链**——一串有序的 handler,每个负责一道防护或最终落盘。这是整个框架的脊柱。

## 顺序的单一真理源:HandlerOrder

顺序不靠魔法数字,而是由 `HandlerOrder` 枚举集中定义,**间隔 100**(便于在两步之间插入新 handler):

`src/main/java/io/github/davidhlp/spring/cache/redis/chain/HandlerOrder.java:12`

| 枚举值 | order | 职责 | 防护目标 |
|---|---|---|---|
| `BLOOM_FILTER` | 100 | 布隆过滤,拦截不存在的 key | [[cache-penetration]] |
| `SYNC_LOCK` | 200 | 分布式锁,串行化回源 | [[cache-breakdown]] |
| `EARLY_EXPIRATION` | 250 | 热 key 提前异步刷新 | [[hot-key]] / [[cache-avalanche]] |
| `TTL` | 300 | TTL 随机抖动 | [[cache-avalanche]] |
| `NULL_VALUE` | 400 | 空值缓存 | [[cache-penetration]] |
| `ACTUAL_CACHE` | 500 | 实际 Redis 读写(链尾) | — |

> 「间隔 100」是约定:想插入新机制(如在锁之后、提前过期之前),给一个 `260` 这样的值即可,无需重排现有顺序。详见 [[add-protection-handler]]。

## 注解绑定:@HandlerPriority

每个具体 handler 用 `@HandlerPriority(HandlerOrder.X)` 声明自己的档位。例如:

```java
@Component
@RequiredArgsConstructor
@HandlerPriority(HandlerOrder.SYNC_LOCK)
public class SyncLockHandler extends AbstractCacheHandler { ... }
```

`@Component` 让它被 Spring 扫描,`@HandlerPriority` 让链工厂认得它该排哪一档。

## 类层次(ADR-0009 后双层 seam:Engine 推进 + Observer 观测)

```
CacheHandler (接口: handle / getNext / setNext)
   └── AbstractCacheHandler (抽象, handle 退化为 shouldHandle ? doHandle : continueChain)
          ├── BloomFilterHandler      (100)
          ├── SyncLockHandler         (200)
          ├── EarlyExpirationHandler  (250)
          ├── TtlHandler              (300)
          ├── NullValueHandler        (400)
          └── ActualCacheHandler      (500, 链尾)

ChainObserver (接口: 4 default no-op 钩子)
   ├── NoOpChainObserver            (单例 default)
   ├── MDCStampChainObserver        (aroundChain)
   ├── ChainTimerChainObserver      (aroundChain)
   ├── FiredCounterChainObserver    (perNode)
   └── ChainDebugLogChainObserver   (perNode)

CacheHandlerChain (thin facade)
   └── 委派给 ChainEngine.execute(ctx)
            └── 调用 observer 列表
                  ├── onChainStart
                  ├── beforeNode → handler.handle → afterNode
                  └── onChainEnd → post-process
```

- **`CacheHandler`** —— 接口:`handle(CacheContext)` / `getNext()` / `setNext()`。
- **`AbstractCacheHandler`** —— 抽象基类。**ADR-0009 后** `handle()` 退化为 `shouldHandle ? doHandle : continueChain`,
  链推进(skipRemaining 短路 / decision switch / 推进 / DEBUG log / fired counter)**全部迁出到 `ChainEngine`**。
  子类只实现 `shouldHandle` / `doHandle` 两个钩子,自身不再调 `getNext().handle(ctx)`。
- **`CacheHandlerChain`** —— **thin facade**(`@Component`):维护 handler 列表(`addHandler` / `size` / `clear` /
  `getHandlerNames`)+ 委派 `execute` 到 `ChainEngine`。保留 `MDC_REQUEST_ID_KEY` 常量(供 observer 引用)。
- **`ChainEngine`** —— **推进引擎**(`@Component`):节点循环 + decision switch + 观测编排 +
  post-process 遍历。`execute(CacheContext)` 全生命周期;`executeChainFragment(CacheContext, CacheHandler)` 供
  `SyncLockHandler` 锁内推进(跳过 aroundChain 观测,避免重复 stamp MDC / record Timer)。
- **`ChainObserver`** —— 4 default no-op 钩子接口:
  - `onChainStart(ctx)` / `onChainEnd(ctx, result)` —— aroundChain(MDC / Timer)
  - `beforeNode(handler, ctx)` / `afterNode(handler, ctx, result)` —— perNode(DEBUG / fired counter)
  - 5 个标准实现见 `chain/observer/` 子包。
- **WS-1.4 OTel/Span 升级路径**:`SpanObserver implements ChainObserver`(~50 SLOC)→ Engine 0 修改 +
  4 内置 observer 0 修改 + 5+ handler 子类 0 修改。详见 [[observability]] 与 [[ADR-0009]]。

> **可观测性(ADR-0009 后由 `ChainObserver` 收口)**:DEBUG 日志 + fired counter + MDC stamp + Timer
> 全部迁出到 `chain/observer/` 子包的 5 个 observer 实现。`ChainEngine` 在每个节点循环
> 调 `beforeNode` / `afterNode`,在链入口/出口调 `onChainStart` / `onChainEnd`。
> 详见 [[observability]] 与 [[ADR-0009]]。

## 链的装配:CacheHandlerChainFactory

`CacheHandlerChainFactory` 在启动时收集所有 `@Component` + `@HandlerPriority` 的 handler,**按 `order` 升序**串成链,产出 `CacheHandlerChain`。`RedisProCacheWriter` 构造时调用一次 `chainFactory.createChain()` 并**缓存实例**(`cachedChain`),后续每次操作复用同一条链(无需重复装配)。

```
请求 ──▶ BloomFilter ──▶ SyncLock ──▶ EarlyExpiration ──▶ TTL ──▶ NullValue ──▶ ActualCache
 (100)         (200)         (250)            (300)        (400)        (500)
   │                                                                │
   └─ 不存在则 miss 短路                                       最终 Redis 读写
```

## 一次操作怎么穿过链

以 GET 为例,数据流与控制流分别见:

- **数据怎么流动** → [[context-data-flow]](`CacheInput` → `CacheContext` → `CacheOutput`)
- **链怎么决定走/停/跳** → [[handler-result-control]](`HandlerResult` + 属性标记)
- **完整读写路径** → [[cache-lifecycle]]

## 相关

- [[cache-lifecycle]] —— GET/PUT/CLEAN 的端到端路径
- [[handler-result-control]] —— CONTINUE / TERMINATE / SKIP_ALL 三态与短路
- [[context-data-flow]] —— 上下文数据模型
- [[auto-configuration]] —— 链如何随 Spring Boot 自动装配
- [[add-protection-handler]] —— 如何新增一档 handler
- [[observability]] —— 链执行可观测性(`[chain]` DEBUG + MDC requestId + `resicache.handler.fired` counter)
