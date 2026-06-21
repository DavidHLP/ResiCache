---
title: 可观测性(observability)
type: modules
tags:
  - module
  - HealthIndicator
  - actuator
  - MeterRegistry
  - 健康检查
related: [auto-configuration, configuration, cache-core, early-expiration]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/observability/RedisCacheHealthIndicator.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/MetricsAutoConfiguration.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 可观测性

ResiCache 的可观测性走 Spring Boot actuator 既有体系,不发明独立指标框架。

> ⚠️ `CLAUDE.md`/`README` 提及的独立 `CacheMetricsRecorder` 类在 `a5ab55b` 重构后已不存在——指标记录实际分散在各 handler 内(经 Spring `CacheStatisticsCollector`),健康检查由 `RedisCacheHealthIndicator` 提供。本页以代码为准。

## 两个组件

### RedisCacheHealthIndicator

`src/main/java/io/github/davidhlp/spring/cache/redis/observability/RedisCacheHealthIndicator.java:25`

```java
@ConditionalOnClass(HealthIndicator.class)
public class RedisCacheHealthIndicator implements HealthIndicator { ... }
```

实现 Spring Boot `HealthIndicator`,在 actuator `/health` 端点暴露 Redis 缓存的连通性/状态。`@ConditionalOnClass` 保证 actuator 不在 classpath 时该 bean 不创建。

### MetricsAutoConfiguration

`src/main/java/io/github/davidhlp/spring/cache/redis/config/MetricsAutoConfiguration.java:37`

```java
@ConditionalOnClass({MeterRegistry.class, HealthIndicator.class})
public class MetricsAutoConfiguration { ... }
```

仅当 Micrometer `MeterRegistry` 与 actuator 都在时生效,装配指标与健康检查 bean。

## 指标如何产生

没有集中式 recorder,指标在**使用点就地记录**,经 Spring Data Redis 的 `CacheStatisticsCollector`:

- [[bloom-filter]] `handleGet`:布隆拒绝时 `statistics.incMisses(cacheName)`;
- [[early-expiration]] 同步刷新:`statistics.incMisses(cacheName)`;
- `RedisProCacheWriter` 持 `CacheStatisticsCollector` 引用,GET/PUT 命中未命中都累计。

这些统计经 `CacheStatisticsCollector` 暴露到 actuator `cache` 端点(Spring Data Redis 标准机制)。Micrometer `MeterRegistry` 在时由 `MetricsAutoConfiguration` 进一步桥接为时序指标。

## 前置条件

两项都 `@ConditionalOnClass`,所以:

- 不引入 `spring-boot-starter-actuator` → 无健康检查,无 `/health` 缓存信息;
- 不引入 Micrometer → 无 `MeterRegistry`,指标降级(`RedisProCacheConfiguration` 用 `ObjectProvider.getIfAvailable()`,见 [[auto-configuration]])。

## 相关

- [[auto-configuration]] —— 装配条件与降级
- [[configuration]] —— 无独立配置项(走 actuator 默认)
- [[cache-core]] —— `RedisProCacheWriter` 持 `CacheStatisticsCollector`
- [[early-expiration]] —— `getThreadPoolStats()` / `getActiveCount()` 可作为补充观测
