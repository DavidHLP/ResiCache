---
title: 注解与注解处理(annotation + handler)
type: modules
tags: [注解, RedisCacheable, AnnotationHandler, RedisCacheOperationSource]
related: [operations, auto-configuration, chain-of-responsibility, configure-behavior]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheable.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheEvict.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCachePut.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCaching.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheOperationSource.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/handler/AnnotationHandler.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 注解与注解处理

ResiCache 提供四个缓存注解,语义对齐 Spring 原生(`@Cacheable`/`@CachePut`/`@CacheEvict`/`@Caching`)并**扩展防护开关**。它们被 `AnnotationHandler` 责任链解析成 `CacheOperation`,经 `RedisCacheOperationSource` 桥接进 Spring Cache 抽象。

## 四个注解

| 注解 | 对应 Spring | 作用 |
|---|---|---|
| `@RedisCacheable` | `@Cacheable` | 方法结果缓存;未命中回源后回填 |
| `@RedisCachePut` | `@CachePut` | 强制把返回值写入缓存(不挡方法执行) |
| `@RedisCacheEvict` | `@CacheEvict` | 淘汰缓存(单 key 或 `allEntries` 全清) |
| `@RedisCaching` | `@Caching` | 一个方法上组合多个上述操作 |

## @RedisCacheable 全属性

`src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheable.java` —— 防护开关都集中在这里:

| 属性 | 默认值 | 含义 | 触发机制 |
|---|---|---|---|
| `value` / `cacheNames` | `{}` | 缓存名 | — |
| `key` | `""` | 缓存 key(SpEL) | — |
| `keyGenerator` / `cacheManager` / `cacheResolver` | `""` | 自定义 bean | — |
| `condition` / `unless` | `""` | SpEL 条件 | [[operations]] |
| `sync` | `false` | 开启击穿保护 | [[breakdown-lock]] |
| `syncTimeout` | `10` | 锁等待超时(秒) | [[breakdown-lock]] |
| `ttl` | `60` | 基础 TTL(秒) | [[ttl-jitter]] |
| `type` | `Object.class` | 缓存值类型 | [[serialization]] |
| `cacheNullValues` | `false` | 空值缓存 | [[null-value]] |
| `useBloomFilter` | `false` | 布隆过滤 | [[bloom-filter]] |
| `expectedInsertions` | `10000` | 布隆容量 | [[bloom-filter]] |
| `falseProbability` | `0.03` | 布隆误判率 | [[bloom-filter]] |
| `randomTtl` | `false` | TTL 抖动 | [[ttl-jitter]] |
| `variance` | `0.2` | 抖动幅度(±20%) | [[ttl-jitter]] |
| `enableEarlyExpiration` | `false` | 提前过期 | [[early-expiration]] |
| `earlyExpirationThreshold` | `0.3` | 触发阈值(剩余占比) | [[early-expiration]] |
| `earlyExpirationMode` | `SYNC` | 刷新模式 | [[early-expiration]] |

```java
@RedisCacheable(value = "users", key = "#id",
    useBloomFilter = true, cacheNullValues = true,
    randomTtl = true, variance = 0.2f,
    enableEarlyExpiration = true, earlyExpirationMode = EarlyExpirationMode.ASYNC)
public User getUserById(Long id) { ... }
```

`@RedisCachePut` / `@RedisCacheEvict` / `@RedisCaching` 的完整属性见各自源文件;它们共享 `value`/`key`/`condition` 等基础属性,`@RedisCacheEvict` 额外有 `allEntries`(→ [[cache-lifecycle]] 的 CLEAN 操作)、`beforeInvocation`。

## 注解解析:AnnotationHandler 责任链

`src/main/java/io/github/davidhlp/spring/cache/redis/handler/AnnotationHandler.java:10`

**注意:这是一条独立的责任链,与防护机制那条(`CacheHandler` 链)不同。** 它负责「解析方法上的注解 → 产出 `List<CacheOperation>`」,运行于方法调用时:

```java
public abstract class AnnotationHandler {
    protected AnnotationHandler next;
    public AnnotationHandler setNext(AnnotationHandler next) { this.next = next; return next; }

    public List<CacheOperation> handle(Method method, Object target, Object[] args) {
        List<CacheOperation> operations = new ArrayList<>();
        if (canHandle(method)) {
            List<CacheOperation> r = doHandle(method, target, args);
            if (r != null) operations.addAll(r);
        }
        if (next != null) {
            List<CacheOperation> nr = next.handle(method, target, args);
            if (nr != null) operations.addAll(nr);
        }
        return operations;
    }
    protected abstract boolean canHandle(Method method);
    protected abstract List<CacheOperation> doHandle(Method method, Object target, Object[] args);
}
```

四个具体处理器(`Cacheable/Put/Evict/Caching AnnotationHandler`)各认自己的注解,组合出本次调用要执行的全部操作。一条链跑完,聚合结果交给 Spring Cache 执行。

## 桥接 Spring:RedisCacheOperationSource

`src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheOperationSource.java:32`

继承 Spring `AnnotationCacheOperationSource`,是 Spring Cache 抽象认得的「操作源」。它把 ResiCache 注解翻译成 Spring 能消费的 `CacheOperation`(见 [[operations]]),让 `RedisCacheInterceptor` 能在标准流程里调用 ResiCache 增强的 `RedisProCache`。

## 原生注解兼容:native-annotation-mode

`resi-cache.native-annotation-mode`(`FULL`/`NONE`/`SELECTIVE`,见 [[configuration]])控制是否把 Spring 原生 `@Cacheable` 等也走 ResiCache 解析。`FULL`(默认)下,老代码零改动即可享受防护。详见 [[auto-configuration]]。

## 相关

- [[operations]] —— 解析出的 `CacheOperation` 如何承载防护配置
- [[auto-configuration]] —— `RedisCacheOperationSource` bean 的装配
- [[chain-of-responsibility]] —— 区别于本页的「防护责任链」
- [[configure-behavior]] —— 如何组合这些注解属性
