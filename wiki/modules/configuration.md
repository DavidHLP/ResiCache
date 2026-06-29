---
title: 配置体系(RedisProCacheProperties)
type: modules
tags:
  - module
  - 配置
  - ConfigurationProperties
  - resi-cache
  - 三层配置
related: [auto-configuration, annotations, configure-behavior, serialization]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheProperties.java
status: stable
created: 2026-06-21
updated: 2026-06-29
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
    rebuild-window-seconds: 30   # CLEAN 后布隆重建窗口(秒);0=禁用(旧行为)。见 WS-1.2c
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
    local-only: false          # true = 无 Redisson 时显式单 JVM 降级(否则 fail-fast)
```
→ [[breakdown-lock]]。`prefix` 决定 Redisson 锁 key 前缀,`timeout` + `unit` 是默认锁等待。

`local-only`(默认 `false`)控制无分布式锁后端(Redisson 缺失 → 无 LockManager bean)时 `sync=true` 的行为:**默认 fail-fast**(拒绝静默退化为单 JVM,多实例下无法防击穿——"标榜分布式却单机"是最坏失败模式);设 `true` 显式接受单 JVM 同步降级(单实例/测试场景),并发 `protection.degraded=local-only` 告警(WS-1.4 升级为 Observation 事件)。

### redisson / redis-deployment(Redis 连接)

Redisson 客户端与 Redis 部署形态(单机/哨兵/集群)配置,由 [[auto-configuration]] 的 `RedisConnectionConfiguration` 消费。

### serializer(序列化安全)

```yaml
  serializer:
    allowed-package-prefixes: [io.github.davidhlp]   # 反序列化白名单(支持 .* 通配)
    fail-on-unknown-type: true                       # 未知类型是否抛错(默认 true,降级到 miss 由 [[cache-lifecycle]] 处理)
    type-property: "@class"                          # 类型判定属性名
    polymorphic-typing-enabled: false                # 多态类型开关(默认 false)
```
→ [[serialization]],防 Jackson 多态类型攻击。

`allowed-package-prefixes` 通配(R9 起):`com.example.*` 匹配 `com.example.Foo` 与任意深度的 `com.example.sub.bar.Qux`(`WhitelistPolicy.matchesPrefix` dot-boundary 保护);literal 前缀如 `com.example` 沿用 `String.startsWith` 语义(intentional,候选 4 dot-boundary 仍 deferred as BREAKING)。

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

## 启动期守卫(SerializerWhitelistStartupGuard,R15)

`src/main/java/io/github/davidhlp/spring/cache/redis/config/SerializerWhitelistStartupGuard.java` — `@Component`,监听 `ApplicationReadyEvent`,检查 `resi-cache.serializer.allowed-package-prefixes` 是否为 `null` 或 `[]`,若空则发 **WARN** 日志,提示用户补回 host app root package。提示包含:

> Set the property to include your host application's root package (e.g. `com.example.*` for wildcard, or `com.example.dto` for literal). Default `[io.github.davidhlp]` only covers ResiCache framework internal types.

谓词 `shouldWarn()` package-private 供单元测试。不动 default value(默认仍是 `[io.github.davidhlp]`),不改 property key,**非 breaking** 改动。本守卫是 [[serialization]] 的运行时补充,也是指南 §4 「whitelist auto-derive」完整项(BEAN FACTORY 自推导 + WARN + explicit override authoritative,标 ⚠️ BREAKING)的 WARN scaffolding 部分。

> 注意:这是 ResiCache 第二个 startup 守卫;另一个是 `SyncLockProperties.localOnly` 启动期告警(在 `RedisProCacheConfiguration` 装配时,见 [[breakdown-lock]])。两个 WARN 各自独立、各自防御一个 misconfig footgun,非重复。

## 相关

- [[auto-configuration]] —— 这些配置如何被装配消费(含 `SerializerWhitelistStartupGuard` 装配上下文)
- [[annotations]] —— 注解级覆盖(最高优先级)
- [[configure-behavior]] —— 三层配置的实操组合
- [[serialization]] —— `serializer.*` 子节详解
