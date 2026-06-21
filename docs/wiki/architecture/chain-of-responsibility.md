---
title: 责任链架构
type: architecture
tags: [责任链, handler, HandlerOrder, 核心架构]
related: [cache-lifecycle, context-data-flow, handler-result-control, auto-configuration, add-protection-handler]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/HandlerOrder.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/AbstractCacheHandler.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/CacheHandlerChainFactory.java
status: stable
created: 2026-06-21
updated: 2026-06-21
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

## 类层次

```
CacheHandler (接口)
   └── AbstractCacheHandler (抽象,模板方法 handle)
          ├── BloomFilterHandler      (100)
          ├── SyncLockHandler         (200)
          ├── EarlyExpirationHandler  (250)
          ├── TtlHandler              (300)
          ├── NullValueHandler        (400)
          └── ActualCacheHandler      (500, 链尾, shouldHandle 恒 true)
```

- **`CacheHandler`** —— 接口:`handle(CacheContext)` / `getNext()` / `setNext()`。
- **`AbstractCacheHandler`** —— 抽象基类,提供 `handle()` 模板方法,把「判断 + 执行 + 控制流」固定下来,子类只实现两个钩子。

### 模板方法 handle()

`src/main/java/io/github/davidhlp/spring/cache/redis/chain/AbstractCacheHandler.java:60`

```java
public HandlerResult handle(CacheContext context) {
    if (context.isSkipRemaining()) {            // ① 已被标记跳过 → 直接放过
        return HandlerResult.continueWith(CacheResult.success());
    }
    if (shouldHandle(context)) {                 // ② 本 handler 是否感兴趣
        HandlerResult result = doHandle(context);
        if (result.decision() == ChainDecision.CONTINUE && getNext() != null) {
            return getNext().handle(context);   //    继续 → 走下一个
        }
        if (result.decision() == ChainDecision.SKIP_ALL) {
            context.markSkipRemaining();         //    跳过剩余 → 标记上下文
        }
        return result;                           //    TERMINATE / SKIP_ALL → 在此返回
    }
    if (getNext() != null) {                     // ③ 不处理 → 交给下一个
        return getNext().handle(context);
    }
    return HandlerResult.continueWith(CacheResult.success());  // 链尾兜底
}
```

子类只需实现:
- `shouldHandle(CacheContext)` —— 本 handler 要不要管这次操作(看操作类型、注解开关)。
- `doHandle(CacheContext)` —— 真正的防护逻辑,返回 `HandlerResult` 控制链路走向。

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
