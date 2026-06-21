---
title: 操作与工厂(operation + factory)
type: modules
tags: [CacheOperation, OperationFactory, RedisCacheRegister, 元数据]
related: [annotations, chain-of-responsibility, context-data-flow, cache-core]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/operation/RedisCacheableOperation.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/operation/RedisCachePutOperation.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/operation/RedisCacheEvictOperation.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/operation/RedisCacheRegister.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/factory/OperationFactory.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 操作与工厂(operation + factory)

注解是「声明」,运行时需要一份「配置实例」随请求流动——这就是 `Operation`。本包把注解解析成扩展了防护字段的 Operation,并按缓存名注册,供责任链各 handler 读取。

## 三个 Operation

均继承 Spring 对应的 `CacheOperation` 子类,扩展 ResiCache 防护字段:

| 类 | 继承 | 扩展字段(节选) |
|---|---|---|
| `RedisCacheableOperation` | `CacheableOperation` | `sync`/`syncTimeout`/`ttl`/`useBloomFilter`/`expectedInsertions`/`falseProbability`/`randomTtl`/`variance`/`enableEarlyExpiration`/`earlyExpirationThreshold`/`earlyExpirationMode` |
| `RedisCachePutOperation` | `CachePutOperation` | 同上(PUT 语义) |
| `RedisCacheEvictOperation` | `CacheEvictOperation` | `allEntries`/`beforeInvocation` 等 |

`src/main/java/io/github/davidhlp/spring/cache/redis/operation/RedisCacheableOperation.java:16`

```java
public class RedisCacheableOperation extends CacheableOperation {
    ...
    public static class Builder extends CacheableOperation.Builder { ... }
}
```

每个 Operation 都带 `Builder`(继承 Spring 的 Builder),由工厂从注解属性填充。这份 Operation 实例最终进入 `CacheContext.cacheOperation`,被各 handler 读取:

- [[breakdown-lock]] 读 `isSync()` / `getSyncTimeout()`;
- [[bloom-filter]] 读 `isUseBloomFilter()`;
- [[ttl-jitter]] 读 `getTtl()` / `isRandomTtl()` / `getVariance()`;
- [[early-expiration]] 读 `isEnableEarlyExpiration()` / `getEarlyExpirationThreshold()` / `getEarlyExpirationMode()`;
- [[null-value]] 读 `isCacheNullValues()`。

> 即:注解属性 → Operation 字段 → `CacheContext` → 各 handler。这条链是「配置如何抵达防护逻辑」的主干,见 [[context-data-flow]]。

## RedisCacheRegister

`src/main/java/io/github/davidhlp/spring/cache/redis/operation/RedisCacheRegister.java:15`

缓存名 → Operation 的注册表。`RedisProCache` 在处理请求时按 cacheName 查回对应 Operation 配置,塞进 `CacheContext`。这让责任链的 handler 不必直接接触注解,只面向 `RedisCacheableOperation` 这份运行时配置。

## OperationFactory

`src/main/java/io/github/davidhlp/spring/cache/redis/factory/OperationFactory.java:15`

泛型接口,把注解翻译成 Operation:

```java
public interface OperationFactory<A extends Annotation, O extends CacheOperation> {
    // 从注解 A 构造操作 O
}
```

三个具体实现:

- `CacheableOperationFactory` —— `@RedisCacheable` → `RedisCacheableOperation`
- `CachePutOperationFactory` —— `@RedisCachePut` → `RedisCachePutOperation`
- `EvictOperationFactory` —— `@RedisCacheEvict` → `RedisCacheEvictOperation`

`src/main/java/io/github/davidhlp/spring/cache/redis/factory/CacheableOperationFactory.java:14`

工厂与 [[annotations]] 的 `AnnotationHandler` 协作:注解处理器认出注解类型,委托对应工厂产出 Operation,聚合后交给 Spring Cache 执行。

## 数据流总览

```
@RedisCacheable(sync=true, useBloomFilter=true, ...)
        │  AnnotationHandler 责任链识别
        ▼
CacheableOperationFactory
        │  填充扩展字段
        ▼
RedisCacheableOperation (sync=true, useBloomFilter=true, …)
        │  RedisCacheRegister 按缓存名登记
        ▼
请求到来 → RedisProCache 查 Register 取 Operation
        │  塞进
        ▼
CacheContext.cacheOperation
        │  责任链各 handler 读取自己关心的字段
        ▼
执行防护 + Redis 落盘
```

## 相关

- [[annotations]] —— 注解侧(声明)与本包(运行时实例)的呼应
- [[context-data-flow]] —— Operation 如何随 `CacheContext` 流动
- [[chain-of-responsibility]] —— handler 消费这些字段
- [[cache-core]] —— `RedisProCache` 如何查 Register
