---
title: 缓存核心(cache 包)
type: modules
tags: [RedisProCache, RedisProCacheManager, RedisProCacheWriter, RedisCacheInterceptor, CachedValue]
related: [cache-lifecycle, auto-configuration, chain-of-responsibility, annotations, serialization]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/cache/RedisProCacheManager.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/cache/RedisProCacheWriter.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/cache/RedisProCache.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/cache/RedisCacheInterceptor.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/cache/CachedValue.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 缓存核心(`cache` 包)

Spring Data Redis 的 `RedisCache` / `RedisCacheManager` / `RedisCacheWriter` 三件套的**增强子类**——在不破坏 Spring Cache 抽象的前提下,把责任链、布隆、锁、注册表接进来。这是 ResiCache 与 Spring Cache 的接合部。

> ⚠️ 本页基于实际代码。`CLAUDE.md`/`README` 曾提及的 `wrapper/`、`spi/`、`CacheMetricsRecorder` 等在 `a5ab55b` 重构后已从代码移除——见 [[log]] 的 lint 记录。

## 五个类

```
RedisCacheManager (Spring)
   └── RedisProCacheManager        ── 产出 RedisProCache
RedisCache (Spring)
   └── RedisProCache               ── 单个缓存实例,接 bloom/register/sync
RedisCacheWriter (Spring, 接口)
   └── RedisProCacheWriter         ── 责任链入口
CacheInterceptor (Spring)
   └── RedisCacheInterceptor       ── AOP 拦截器子类
CachedValue                        ── 值包装(独立 final 类)
```

## RedisProCacheManager

`src/main/java/io/github/davidhlp/spring/cache/redis/cache/RedisProCacheManager.java:20`

继承 Spring `RedisCacheManager`,持有 `RedisProCacheWriter` / `MeterRegistry` / `BloomSupport` / `RedisCacheRegister` / `SyncSupport`。关键是**两个工厂方法都产出 `RedisProCache`** 而非原生 `RedisCache`:

```java
@Override
protected RedisCache createRedisCache(String name, RedisCacheConfiguration cfg) {
    return new RedisProCache(name, redisProCacheWriter, resolveCacheConfiguration(cfg),
            meterRegistry, bloomSupport, redisCacheRegister, syncSupport);
}

@Override
protected RedisCache getMissingCache(String name) {   // 懒加载未预声明的缓存
    return new RedisProCache(name, redisProCacheWriter, resolveCacheConfiguration(null),
            meterRegistry, bloomSupport, redisCacheRegister, syncSupport);
}
```

这样无论缓存是预声明(`resi-cache.caches.<name>`)还是运行时懒加载,都走 ResiCache 增强逻辑。

## RedisProCacheWriter —— 责任链入口

`src/main/java/io/github/davidhlp/spring/cache/redis/cache/RedisProCacheWriter.java:52`

实现 Spring `RedisCacheWriter`,但每个操作都**转译成 `CacheContext` 交给责任链**:

```java
public RedisProCacheWriter(RedisTemplate<String,Object> redisTemplate,
                           ValueOperations<String,Object> valueOperations,
                           CacheStatisticsCollector statistics,
                           RedisCacheRegister redisCacheRegister,
                           TypeSupport typeSupport,
                           CacheHandlerChainFactory chainFactory) {
    ...
    this.cachedChain = chainFactory.createChain();   // 启动时装配一次,复用
}

public byte[] get(String name, byte[] key, Duration ttl) {
    CacheContext context = buildContext(CacheOperation.GET, name, redisKey, actualKey, null, null, ttl);
    CacheResult result = getChain().execute(context);
    return result.getResultBytes();
}
```

- `cachedChain` —— 装配一次的责任链实例,所有请求复用(无需重复构建)。
- `retrieve(...)` 异步版用 `CompletableFuture.supplyAsync(() -> get(...))`。
- 读路径见 [[cache-lifecycle]]。

## RedisProCache —— 单实例增强

继承 Spring `RedisCache`,在标准 get/put 之外接入:

- **布隆二次拦截**:`get(key, valueLoader)` 在调用业务 loader **前**再做一次布隆检查(sync 模式下防止未命中仍触发数据源查询),与 [[bloom-filter]] 的 Writer 层短路互补。
- **sync 协调**:持 `SyncSupport` 引用,但真正加锁由责任链里的 [[breakdown-lock]] 主导。
- **操作注册查询**:持 `RedisCacheRegister`,按 cacheName 取回该缓存的 `RedisCacheableOperation` 配置(sync/bloom/ttl 等),塞进 `CacheContext.cacheOperation` 供各 handler 读取。

## RedisCacheInterceptor

`src/main/java/io/github/davidhlp/spring/cache/redis/cache/RedisCacheInterceptor.java:28`

继承 Spring `CacheInterceptor`(AOP 切面),桥接 ResiCache 的注解解析(`RedisCacheOperationSource`)到 Spring Cache 调用链。它决定「这个方法调用要不要走缓存、走哪些操作」。

## CachedValue —— 值包装

`src/main/java/io/github/davidhlp/spring/cache/redis/cache/CachedValue.java:19`

`final` 类,所有写入 Redis 的值都包成它。带 `@JsonTypeInfo(@class)` 以便安全多态反序列化:

- `value` —— 真实业务值;
- `createdTime` / `ttl` —— 元数据,供 [[early-expiration]] 判定、[[null-value]] 还原;
- `checkExpired()` / `getRemainingTtl()` —— 过期判定;
- 内置 `CachedValueBuilder`。

## 相关

- [[cache-lifecycle]] —— Writer 的 get/put 端到端路径
- [[auto-configuration]] —— 这三个 bean 如何被装配
- [[chain-of-responsibility]] —— Writer 把操作交给链
- [[serialization]] —— `CachedValue` 如何被安全序列化
- [[annotations]] —— `RedisCacheOperationSource` 如何解析注解
