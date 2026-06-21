---
title: 如何配置缓存行为
type: how-to
tags: [配置, 教程, 三层配置]
related: [configuration, annotations, auto-configuration]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheProperties.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheable.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 如何配置缓存行为

ResiCache 的配置分三层,优先级:**注解级 > per-cache > 全局**。从粗到细覆盖。

## 层 1:全局默认(`application.yml`)

`resi-cache.*` 设兜底默认,所有缓存共享:

```yaml
resi-cache:
  default-ttl: 30m
  key-prefix: "app:"
  native-annotation-mode: FULL
  bloom-filter:
    enabled: false              # 全局默认关闭,按需在注解/per-cache 开
  serializer:
    allowed-package-prefixes: [io.github.davidhlp, com.mycompany]
```

完整项见 [[configuration]]。

## 层 2:per-cache 覆盖(`caches.<name>`)

对具名缓存单独覆盖,不改代码:

```yaml
resi-cache:
  caches:
    users:
      ttl: 1h
      cache-null-values: true
      enable-bloom-filter: true
    products:                   # 热点商品缓存
      ttl: 10m
      enable-early-expiration: true
```

`buildInitialCacheConfigurations` 读它,为每个具名缓存产出独立 `RedisCacheConfiguration`(见 [[auto-configuration]])。

## 层 3:注解级(方法粒度,最高优先级)

`@RedisCacheable` 等属性覆盖一切:

```java
@RedisCacheable(value = "users", key = "#id",
    ttl = 120,                  // 覆盖 caches.users.ttl 与 default-ttl
    useBloomFilter = true,
    randomTtl = true, variance = 0.2f)
public User getUser(Long id) { ... }
```

全属性表见 [[annotations]]。

## 典型套餐

### 防穿透套餐(用户查询,ID 可枚举)

```java
@RedisCacheable(value = "users", key = "#id",
    useBloomFilter = true, cacheNullValues = true)
```

### 防雪崩套餐(批量预热的商品)

```yaml
resi-cache:
  default-ttl: 30m
  caches:
    products:
      ttl: 30m
```
```java
@RedisCacheable(value = "products", key = "#id",
    randomTtl = true, variance = 0.2f)
```

### 热 key 套餐(爆款/热搜)

```java
@RedisCacheable(value = "trending", key = "#id",
    enableEarlyExpiration = true,
    earlyExpirationMode = EarlyExpirationMode.ASYNC,
    sync = true)                // 兜底:真过期了也串行化
```

### 击穿保护(高价值单 key)

```java
@RedisCacheable(value = "config", key = "#key", sync = true)
```

## 优先级规则速查

| 同一项 | 生效来源 |
|---|---|
| 注解显式设值 | 注解(最高) |
| 注解未设 + per-cache 设 | per-cache |
| 都未设 | 全局 `resi-cache.*`(最低) |

> 注意 `Boolean` 型 per-cache 项(如 `cache-null-values`):`null` = 继承全局,`false` = 显式关闭。仅 `false` 才真正覆盖。

## 相关

- [[configuration]] —— 全量配置项参考
- [[annotations]] —— 注解全属性
- [[auto-configuration]] —— 配置如何被装配
