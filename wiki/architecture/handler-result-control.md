---
title: 链路控制(HandlerResult)
type: architecture
tags:
  - architecture
  - HandlerResult
  - ChainDecision
  - 短路
  - PostProcessHandler
  - 控制流
related: [chain-of-responsibility, context-data-flow, cache-lifecycle]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/HandlerResult.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/ChainDecision.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/AbstractCacheHandler.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/PostProcessHandler.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 链路控制:HandlerResult 与 ChainDecision

责任链不只是「一个接一个跑」,它有三态控制流:继续、终止、跳过剩余。理解这三态 + 属性标记 + 后置回调,才能读懂短路、锁内聚、提前过期这些设计。

## 三态:ChainDecision

`src/main/java/io/github/davidhlp/spring/cache/redis/chain/ChainDecision.java`

| 决策 | 含义 | 典型场景 |
|---|---|---|
| `CONTINUE` | 本 handler 处理完(或没处理),把控制权交给下一个 | 大多数情况 |
| `TERMINATE` | 本 handler 产出最终结果,**链在此停止**,不再走后续 | [[bloom-filter]] 判定 key 不存在 → 直接 miss;[[cache-lifecycle]] 的 ActualCache 落盘后 |
| `SKIP_ALL` | 标记「跳过所有剩余 handler」(但仍可能触发后置) | [[early-expiration]] 同步刷新 → 让 ActualCache 返回 miss |

## HandlerResult 工厂方法

`src/main/java/io/github/davidhlp/spring/cache/redis/chain/HandlerResult.java`

handler 在 `doHandle()` 末尾返回一个 `HandlerResult`,由工厂方法构造:

- `HandlerResult.continueChain()` —— CONTINUE,不带结果(继续链)
- `HandlerResult.continueWith(CacheResult)` —— CONTINUE,带结果
- `HandlerResult.terminate(CacheResult)` —— TERMINATE,带最终结果
- `HandlerResult.skipAll()` —— SKIP_ALL

`AbstractCacheHandler.handle()` 模板据此决定走向(见 [[chain-of-responsibility]] 的模板代码):CONTINUE 且有 next → 调 `getNext().handle()`;SKIP_ALL → `context.markSkipRemaining()`;否则原样返回。

## 控制流三种典型短路

### 1. 布隆短路(TERMINATE)

[[bloom-filter]] `handleGet`:若布隆判定 key 不可能存在,直接 `terminate(CacheResult.miss())`,跳过后续 Redis 查询与锁:

```java
if (!mightContain) {
    statistics.incMisses(context.getCacheName());
    return HandlerResult.terminate(CacheResult.miss());
}
```

### 2. 同步提前过期(SKIP_ALL → miss)

[[early-expiration]] 检测到热 key 需要同步刷新时:

```java
context.setAttribute(CacheContext.AttributeKey.EARLY_EXPIRATION_SKIPPED, true);
return HandlerResult.skipAll();
```

`markSkipRemaining()` 被设置后,链尾 [[cache-lifecycle]] 的 `ActualCacheHandler` 看到 `EARLY_EXPIRATION_SKIPPED` 标记,直接返回 miss,触发上层同步回源刷新。

### 3. 锁内聚(CONTINUE 进锁,TERMINATE 出锁)

[[breakdown-lock]] 的 `SyncLockHandler` 不靠决策短路,而是**把整条剩余链包进锁里执行**:

```java
context.setAttribute("sync.lock.acquired", true);          // 标记,防下游重复加锁
CacheResult result = syncSupport.executeSync(lockKey,
    () -> executeChainInLock(context),                     // 锁内跑后续 handler
    timeoutSeconds);
return HandlerResult.terminate(result);                    // 锁内结果即最终结果
```

`executeChainInLock` 内部调 `getNext().handle(context)`——因为已设了 `sync.lock.acquired`,下游 `SyncLockHandler.shouldHandle()` 会返回 false,避免重复加锁。

## 属性标记:handler 间的「握手」

控制流常依赖 [[context-data-flow]] 的属性袋做隐式协作:

| 标记 | 谁设 | 谁读 | 控制效果 |
|---|---|---|---|
| `sync.lock.acquired` | SyncLockHandler | SyncLockHandler 自身 | 锁内不重复加锁 |
| `EARLY_EXPIRATION_SKIPPED` | EarlyExpirationHandler | ActualCacheHandler | 链尾返回 miss |
| `PREFETCHED_CACHED_VALUE` | EarlyExpirationHandler | ActualCacheHandler | 复用预取值,免二次 GET |
| `bloom.postProcess` | BloomFilterHandler | BloomFilterHandler 后置 | 标记需后置加 key |
| `context.isSkipRemaining()` | AbstractCacheHandler(markSkipRemaining) | 所有 handler 模板 | 跳过剩余 |

这种「写标记 + 读标记」是松耦合协作的核心——新增 handler 不用改老 handler,只要约定新标记。

## 后置处理:PostProcessHandler

有些逻辑必须在**整条链跑完后**执行(比如「确认 PUT 成功了,再把 key 加进布隆过滤器」)。实现 `PostProcessHandler` 接口的 handler 提供两个钩子:

```java
public interface PostProcessHandler {
    boolean requiresPostProcess(CacheContext context);              // 是否需要后置
    void afterChainExecution(CacheContext context, CacheResult r);  // 链结束后回调
}
```

[[bloom-filter]] 是典型实现:PUT 时只设 `bloom.postProcess=true` 并 `continueChain()`;链跑完且成功后,`afterChainExecution` 才真正 `bloomSupport.add(...)`。这样**只在数据确实写进 Redis 后**才更新布隆,GET miss 不会污染过滤器。

## 错误处理与降级

每个 handler 的实际操作包了 try/catch,异常经 `CacheErrorHandler` 转成降级结果(如 GET 失败 → miss,而非向上抛)。这与 `TERMINATE` 配合,保证单点故障不击穿整条链。真正的跨请求级故障隔离(熔断/限流)由使用方在业务层实现(当前框架未内置独立熔断组件,见 [[cache-avalanche]] 说明)。

## 相关

- [[chain-of-responsibility]] —— `handle()` 模板如何消费这些决策
- [[context-data-flow]] —— 属性袋的完整约定
- [[cache-lifecycle]] —— miss / hit 如何回传上层
