---
title: 提前过期(250)
type: mechanisms
tags:
  - mechanism
  - 提前过期
  - 热key
  - EARLY_EXPIRATION
  - 异步刷新
  - Lua
related: [hot-key, cache-avalanche, ttl-jitter, handler-result-control]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/refresh/EarlyExpirationHandler.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/refresh/EarlyExpirationSupport.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/refresh/ThreadPoolEarlyExpirationExecutor.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/avalanche/TtlPolicy.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 提前过期(HandlerOrder 250)

责任链**第三档**(插在锁与 TTL 之间)。热点 key 在**真正过期之前**就主动刷新,让用户永远几乎看不到 miss——既护 [[hot-key|热 key]] 命中率,也削平过期瞬间的 [[cache-avalanche|雪崩]] 峰值。支持同步与异步两种刷新模式。

## 判定:何时该提前刷新

`EarlyExpirationHandler` 只对 `GET` 且注解 `enableEarlyExpiration=true` 生效。它先 `valueOperations.get` 预取缓存值,交给 `TtlPolicy.shouldEarlyExpiration` 判断:

`src/main/java/io/github/davidhlp/spring/cache/redis/protection/refresh/EarlyExpirationHandler.java:111`

```java
private EarlyExpirationDecision checkEarlyExpiration(CacheContext context, CachedValue cachedValue) {
    boolean shouldRefresh = ttlPolicy.shouldEarlyExpiration(
        cachedValue.getCreatedTime(),
        cachedValue.getTtl(),
        context.getCacheOperation().getEarlyExpirationThreshold());  // 默认 0.3
    if (!shouldRefresh) return EarlyExpirationDecision.noRefresh();
    EarlyExpirationMode mode = resolveMode(context);
    if (mode == EarlyExpirationMode.ASYNC) {
        scheduleAsyncRefresh(context, cachedValue);
        return EarlyExpirationDecision.asyncRefresh();
    }
    statistics.incMisses(context.getCacheName());
    return EarlyExpirationDecision.syncRefresh();
}
```

`earlyExpirationThreshold`(默认 `0.3`)是**剩余 TTL 占比阈值**:当剩余 TTL < 总 TTL × 0.3 时(即已用掉 70%),触发提前刷新。

## 两种刷新模式

### SYNC(默认)—— 同步刷新

返回 `HandlerResult.skipAll()`,并设 `EARLY_EXPIRATION_SKIPPED=true`。链尾 [[cache-lifecycle|ActualCacheHandler]] 看到标记后**返回 miss**,触发上层同步回源(可能配合 [[breakdown-lock]] 加锁)。用户这次请求稍慢,但拿到的是最新值。

### ASYNC —— 异步刷新(推荐用于热 key)

`scheduleAsyncRefresh` 把刷新任务丢进线程池**后台执行**,当前请求**照常返回旧值**(命中),不阻塞用户。代价是这一次返回的可能稍旧,但命中率与延迟都最优。

## 异步刷新的并发安全

多个请求同时发现「该刷新」会重复回源。`EarlyExpirationHandler` 用一段 Lua 脚本做 **CAS 缩短 TTL**,确保只有一个刷新真正执行:

`src/main/java/io/github/davidhlp/spring/cache/redis/protection/refresh/EarlyExpirationHandler.java:52`

```lua
local current = redis.call('get', KEYS[1])
if current == ARGV[1] then           -- 值未变(只有第一个刷新者能匹配)
    redis.call('expire', KEYS[1], ARGV[2])  -- 缩短 TTL,让后续请求直接 miss/等待
    return 1
else
    return 0                          -- 值已变,说明别人已在刷新,本任务放弃
end
```

配合 `atomicShortenTtlIfValueUnchanged` —— 只有 CAS 成功(返回 1)才真正回源,否则说明已有别的线程在刷新,本次跳过。还有 `REFRESH_GRACE_PERIOD_SECONDS = 5` 宽限期:若剩余 TTL 已不足 5 秒,认为即将自然过期,不再异步刷新(等它过期触发正常 miss 即可)。

## 执行器:EarlyExpirationSupport / Executor

`EarlyExpirationSupport`(`@Component`)是门面,把刷新任务交给 `EarlyExpirationExecutor`:

`src/main/java/io/github/davidhlp/spring/cache/redis/protection/refresh/EarlyExpirationSupport.java:24`

```java
public void submitAsyncRefresh(String key, Runnable refreshTask) {
    if (key == null || refreshTask == null) { /* warn */ return; }
    executor.submit(key, refreshTask);
}
```

- `submit(key, task)` —— 按 key 去重(同 key 并发刷新只跑一个);
- `cancel(key)` —— 取消某 key 的待执行任务;
- `getStats()` / `getActiveCount()` —— 线程池观测(见 [[observability]]);
- `@PreDestroy shutdown()` —— 优雅关闭。

`ThreadPoolEarlyExpirationExecutor` 是基于线程池的默认实现,池参数由 `resi-cache.early-expiration.{pool-size,max-pool-size,queue-capacity}` 配置。

## 预取复用:避免双重 GET

`doHandle` 把预取的 `cachedValue` 存进 `PREFETCHED_CACHED_VALUE`(见 [[context-data-flow]])。这样链尾 [[cache-lifecycle]] 的 `ActualCacheHandler.handleGet` 直接复用,**不再多打一次 Redis GET**——提前过期检查的额外开销被消掉了。

## 配置

全局(`resi-cache.early-expiration.*`):

```yaml
resi-cache:
  early-expiration:
    enabled: true
    pool-size: 2
    max-pool-size: 10
    queue-capacity: 100
```

注解级(`@RedisCacheable`):

```java
@RedisCacheable(value = "hotdata", key = "#id",
    enableEarlyExpiration = true,
    earlyExpirationThreshold = 0.3,           // 剩余 30% 时触发
    earlyExpirationMode = EarlyExpirationMode.ASYNC)  // 异步刷新
```

## 与 TTL 抖动的关系

提前过期(主动)与 [[ttl-jitter]](被动打散)是防雪崩的两面:抖动让 key 别同时过期,提前过期让热点 key 别真的过期。两者常配合使用——抖动设 `variance`,热 key 再叠 `enableEarlyExpiration`。

## 相关

- [[hot-key]] —— 热 key 问题与本机制的关系
- [[cache-avalanche]] —— 雪崩防护的另一面
- [[ttl-jitter]] —— 被动打散,与本机制互补
- [[handler-result-control]] —— `skipAll` + `EARLY_EXPIRATION_SKIPPED` 的协作
