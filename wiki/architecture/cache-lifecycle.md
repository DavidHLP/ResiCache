---
title: 缓存生命周期
type: architecture
tags:
  - architecture
  - 生命周期
  - GET
  - PUT
  - CLEAN
  - 数据流
related: [chain-of-responsibility, context-data-flow, handler-result-control, cache-core, configuration]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/cache/RedisProCacheWriter.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/ActualCacheHandler.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/PostProcessHandler.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/cache/RedisProCache.java
status: stable
created: 2026-06-21
updated: 2026-06-29
---

# 缓存生命周期

一次缓存操作从 Spring Cache 抽象入口,到责任链,再到 Redis 落盘的完整路径。配合 [[chain-of-responsibility]] 理解骨架,本页看血肉。

## 入口:RedisProCacheWriter

所有读写最终走 `RedisProCacheWriter`,它实现了 Spring Data Redis 的 `RedisCacheWriter` 接口,但内部把每个操作**转译成 `CacheContext` 丢给责任链**:

> 本页聚焦**运行时 lifecycle**(从第一次 cache 调用开始)。**启动期前**的 misconfig 防御由独立 startup 守卫负责——`SerializerWhitelistStartupGuard`(R15) 在 `ApplicationReadyEvent` 时检查白名单;`SyncLockProperties.localOnly` 启动期告警。它们在 lifecycle 之前生效,见 [[configuration]]。

`src/main/java/io/github/davidhlp/spring/cache/redis/cache/RedisProCacheWriter.java:76`

```java
public byte[] get(String name, byte[] key, Duration ttl) {
    String redisKey  = typeSupport.bytesToString(key);
    String actualKey = extractActualKey(name, redisKey);
    CacheContext context = buildContext(CacheOperation.GET, name, redisKey, actualKey, null, null, ttl);
    CacheResult result = getChain().execute(context);   // 进入责任链
    return result.getResultBytes();
}
```

`retrieve(...)`(异步)则简单包了一层 `CompletableFuture.supplyAsync(() -> get(...))`。

## 操作类型:CacheOperation

链内的 `context.getOperation()` 取这五个值之一,每个 handler 据此分流:

| 操作 | 触发场景 | 终点 ActualCacheHandler 行为 |
|---|---|---|
| `GET` | `@RedisCacheable` 命中读取 | 读 Redis,命中返值 / 未命中返 miss |
| `PUT` | `@CachePut` / 回源后回填 | 写 Redis(应用 TTL) |
| `PUT_IF_ABSENT` | `@Cacheable` 未命中回源后「不存在才写」 | `SETNX` 语义 |
| `REMOVE` | `@CacheEvict` 单 key | `DEL` |
| `CLEAN` | `@CacheEvict(allEntries=true)` | `SCAN` + 批量 `DEL` |

## GET 路径(命中 / 未命中)

`src/main/java/io/github/davidhlp/spring/cache/redis/chain/ActualCacheHandler.java:98`

```java
private CacheResult handleGet(CacheContext context) {
    try {
        // 优先复用 EarlyExpirationHandler 预取的值,避免双重 Redis GET
        CachedValue cachedValue = context.getAttribute(CacheContext.AttributeKey.PREFETCHED_CACHED_VALUE);
        if (cachedValue == null) {
            Object rawValue = valueOperations.get(context.getRedisKey());
            cachedValue = (rawValue instanceof CachedValue cv) ? cv : null;
        }
        if (isCacheHit(cachedValue)) {           // 非空且未过期
            return processCacheHit(context, cachedValue);
        }
        return CacheResult.miss();               // 未命中 → 由上层回源
    } catch (Exception e) {
        return errorHandler.handleGetError(context.getCacheName(), context.getRedisKey(), e);
    }
}
```

关键细节:

- **避免双重 GET**:若上游 [[early-expiration]] 已经 `valueOperations.get` 过一次,会把结果塞进 `PREFETCHED_CACHED_VALUE`,链尾直接复用,不再多打一次 Redis。
- **读路径不写**:`processCacheHit` 默认不刷新 TTL,避免读放大。需要 TTI(读刷新)应启用 Spring Data Redis 的 `enableTimeToIdle()`(Redis 6.2 `GETEX`),而非重写 value。
- **值包装**:读出的 `CachedValue` 经 `DefaultNullValuePolicy.toReturnValue(...)` 还原(空值占位 → null),见 [[null-value]]。

## 命中后:上层回源(Spring Cache 契约)

`ActualCacheHandler` 只负责「读 Redis」。真正的「未命中 → 调用业务方法 → 回填」是 Spring `Cache` 抽象的职责,在 `RedisProCache.get(key, valueLoader)` 层完成。`@RedisCacheable` 在此还会:

- 在调 loader **前**再做一次布隆拦截(防穿透),见 [[bloom-filter]] 的 RedisProCache 层短路;
- sync 模式下用 `RedisProCache` 持有的 `SyncSupport` 协调,锁逻辑仍由 [[breakdown-lock]] 的 `SyncLockHandler` 主导。

## CLEAN 路径:SCAN + 批量删

`@CacheEvict(allEntries = true)` 不给定具体 key,需按前缀清空。`ActualCacheHandler` 用游标扫描 + 分批删除,控制单次 Redis 压力:

```java
private static final int CLEAN_SCAN_COUNT      = 512;   // 每次 SCAN 游标拉取数
private static final int CLEAN_DELETE_BATCH_SIZE = 256; // 每批 DEL 的 key 数
```

`src/main/java/io/github/davidhlp/spring/cache/redis/chain/ActualCacheHandler.java:45`

> CLEAN 还会触发 [[bloom-filter]] 的后置处理(`PostProcessHandler`),清空对应布隆过滤器——但布隆不支持精确删除,清空后短期内会有穿透风险,直到新 PUT 重新填充。

## PUT / PUT_IF_ABSENT 路径

写入时,[[ttl-jitter]] 已在 `CacheOutput.finalTtl` 计算好抖动后的 TTL,[[null-value]] 已把值包成 `CachedValue`(或决定不写)。`ActualCacheHandler` 拿到最终的 `storeValue` 与 `finalTtl` 执行 `SET`(或 `SETNX`)。写入成功后,[[bloom-filter]] 的后置回调把 key 加进过滤器。

## 错误处理

每个操作分支都包了 `try/catch`,异常交给 `CacheErrorHandler`(如 `handleGetError`)——保证 Redis 故障不向上抛穿业务调用(可降级为 miss / 直接回源)。这与业务层的熔断/限流(使用方可配合 Resilience4j 实现,当前框架未内置独立组件,见 [[cache-avalanche]])是两层不同的防护。

> 📁 `[[cache-avalanche]]` 位于 `wiki/concepts/`(concept 分类),不在 `wiki/mechanisms/`(mechanism 分类) — 跟 `[[bloom-filter]]` / `[[breakdown-lock]]` / `[[early-expiration]]` / `[[null-value]]` / `[[ttl-jitter]]` 不同目录;wiki link 按文件名解析照常工作,但分类上「雪崩现象」是 concept(讨论为何),「TTL jitter」是 mechanism(讨论怎么做)。

## 相关

- [[chain-of-responsibility]] —— 链的骨架与顺序
- [[context-data-flow]] —— `buildContext` 造的上下文长什么样
- [[handler-result-control]] —— miss / hit / bloom 拒绝如何控制链路
- [[cache-core]] —— `RedisProCache` / `RedisProCacheManager` / `RedisProCacheWriter` 的分工
- [[configuration]] —— 启动期守卫(`SerializerWhitelistStartupGuard` / `localOnly` 告警)在 lifecycle 之前生效
