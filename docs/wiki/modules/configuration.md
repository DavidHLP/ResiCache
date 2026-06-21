---
title: 配置体系(RedisProCacheProperties)
type: modules
tags: [配置, ConfigurationProperties, resi-cache, 三层配置]
related: [auto-configuration, annotations, configure-behavior, serialization]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheProperties.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 配置体系(`resi-cache.*`)

所有可外部化配置集中在 `RedisProCacheProperties`(`@ConfigurationProperties(prefix = "resi-cache")` + `@Validated`)。三层覆盖:全局默认 → per-cache → 注解级。

`src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheProperties.java`

## 全局配置(顶层)

```yaml
resi-cache:
  default-ttl: 30m              # 默认 TTL(Duration)
  transaction-aware: false      # 事务感知缓存
  key-prefix: ""                # 全局键前缀
  native-annotation-mode: FULL  # 原生注解兼容:FULL / NONE / SELECTIVE
```

## 子配置节

### bloom-filter(布隆)

```yaml
  bloom-filter:
    enabled: true
    expected-insertions: 100000
    false-probability: 0.01
```
→ [[bloom-filter]]

### early-expiration(提前过期)

```yaml
  early-expiration:
    enabled: true
    pool-size: 2
    max-pool-size: 10
    queue-capacity: 100
```
→ [[early-expiration]] 的异步刷新线程池。

### sync-lock(分布式锁)

```yaml
  sync-lock:
    prefix: "resi-cache:lock:"
    timeout: 10s
```
→ [[breakdown-lock]]。`prefix` 决定 Redisson 锁 key 前缀,`timeout` + `unit` 是默认锁等待。

### redisson / redis-deployment(Redis 连接)

Redisson 客户端与 Redis 部署形态(单机/哨兵/集群)配置,由 [[auto-configuration]] 的 `RedisConnectionConfiguration` 消费。

### serializer(序列化安全)

```yaml
  serializer:
    allowed-package-prefixes: [io.github.davidhlp]   # 反序列化白名单
    fail-on-unknown-type: false                      # 未知类型是否抛错
    type-property: "@class"                          # 类型判定属性名
    polymorphic-typing-enabled: true                 # 多态类型开关
```
→ [[serialization]],防 Jackson 多态类型攻击。

## per-cache 覆盖(`caches.<name>`)

对单个具名缓存覆盖全局默认:

```yaml
  caches:
    users:
      ttl: 1h                      # 覆盖 default-ttl
      key-prefix: "u:"             # 覆盖全局前缀
      cache-null-values: true      # 覆盖空值策略
      enable-bloom-filter: true    # 覆盖布隆开关
      enable-early-expiration: true
```

`CacheConfig` 内嵌类承载这些字段。`RedisProCacheConfiguration.buildInitialCacheConfigurations(...)` 读它,为每个具名缓存产出独立 `RedisCacheConfiguration`(见 [[auto-configuration]])。

## 三层配置优先级

| 层 | 位置 | 优先级 |
|---|---|---|
| **注解级** | `@RedisCacheable` 等属性 | 最高(方法粒度) |
| **per-cache** | `resi-cache.caches.<name>` | 中 |
| **全局** | `resi-cache.*` 顶层 | 最低(兜底默认) |

例:某方法 `@RedisCacheable(ttl=120)`,即使 `caches.users.ttl=1h` 和 `default-ttl=30m`,实际 TTL 由注解决定(120s),[[ttl-jitter]] 据此抖动。

> `CacheConfig.cacheNullValues` 用 `Boolean`(可为 null)——`null` 表示「不覆盖,继承全局」,`false` 才真正关闭。`buildInitialCacheConfigurations` 仅在 `Boolean.FALSE` 时调 `disableCachingNullValues()`。

## 启用校验

`@Validated` + JSR-303 约束(如 `default-ttl` 标 `@NotNull`)在启动期 fail-fast。更复杂的缓存注解合法性校验由 `CachingEnablementValidation`(含 `CachingEnabledValidator`)负责,见 [[auto-configuration]]。

## 相关

- [[auto-configuration]] —— 这些配置如何被装配消费
- [[annotations]] —— 注解级覆盖(最高优先级)
- [[configure-behavior]] —— 三层配置的实操组合
- [[serialization]] —— `serializer.*` 子节详解
