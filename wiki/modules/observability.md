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

> ⚠️ `CLAUDE.md`/`README.md` 早期描述的独立 `CacheMetricsRecorder` 类在 `a5ab55b refactor: remove dead code` 后已不存在——指标记录实际分散在各 handler 内(经 Spring `CacheStatisticsCollector`),健康检查由 `RedisCacheHealthIndicator` 提供。本页以代码为准。

## 三个组件

### RedisCacheHealthIndicator

`src/main/java/io/github/davidhlp/spring/cache/redis/observability/RedisCacheHealthIndicator.java:25`

```java
@ConditionalOnClass(HealthIndicator.class)
public class RedisCacheHealthIndicator implements HealthIndicator { ... }
```

实现 Spring Boot `HealthIndicator`,在 actuator `/actuator/health` 端点暴露 ResiCache 的整体健康状态(Redis 连通性、关键 bean 装配状态等)。`@ConditionalOnClass(HealthIndicator.class)` 保证 actuator 不在 classpath 时该 bean 不创建,无副作用。

### MetricsAutoConfiguration

`src/main/java/io/github/davidhlp/spring/cache/redis/config/MetricsAutoConfiguration.java:37`

```java
@ConditionalOnClass({MeterRegistry.class, HealthIndicator.class})
public class MetricsAutoConfiguration { ... }
```

仅当 Micrometer `MeterRegistry` 与 actuator 都在 classpath 时生效,装配指标桥接与健康检查 bean。两者缺一就跳过(零配置自动降级)。

### CacheStatisticsCollector(Spring 提供的统计)

`RedisProCacheWriter` 构造函数注入 Spring Data Redis 的 `CacheStatisticsCollector`,各 handler 在关键路径上调它累计命中 / 未命中:

- [[bloom-filter]] `handleGet`:**布隆拒绝**时 `statistics.incMisses(cacheName)`
- [[early-expiration]] **同步刷新**:`statistics.incMisses(cacheName)`
- `RedisProCacheWriter` 自身:GET / PUT 命中与未命中都累计

这些统计经 `CacheStatisticsCollector` 暴露到 actuator 标准 `cache` 端点。Micrometer `MeterRegistry` 在时由 `MetricsAutoConfiguration` 进一步桥接为时序指标(Prometheus / StatsD 等)。

## 指标如何产生

没有集中式 `CacheMetricsRecorder`,指标在**使用点就地记录**:

```
请求 ──▶ BloomFilterHandler.handleGet
              │ 布隆判定 key 不存在
              └─▶ statistics.incMisses(cacheName)   ← 就地记录
                  
         EarlyExpirationHandler.doHandle (同步模式)
              │ 需同步刷新
              └─▶ statistics.incMisses(cacheName)   ← 就地记录
```

设计意图:**把统计职责留在发生点**,避免中央 recorder 与 handler 间的耦合。Micrometer / `CacheStatisticsCollector` 已经是 Spring Cache 的标准机制,ResiCache 不重复造轮子。

## 前置条件与降级

| 依赖 | 缺时行为 |
|---|---|
| 不引入 `spring-boot-starter-actuator` | 无 `/actuator/health`、无 `RedisCacheHealthIndicator` bean |
| 不引入 Micrometer | 无 `MeterRegistry`,`MetricsAutoConfiguration` 跳过;`RedisProCacheConfiguration` 用 `ObjectProvider.getIfAvailable()` 优雅降级(见 [[auto-configuration]]) |
| 只缺其一 | 该 bean 不创建,其他仍生效(各 `@ConditionalOnClass` 独立判定) |

`@ConditionalOnClass` + `ObjectProvider` 组合保证:**生产全量依赖、测试/精简环境部分缺失,框架都能启动**。

## 生产部署建议

- 启用 actuator:`spring-boot-starter-actuator`
- 暴露 health:`management.endpoint.health.show-details=when-authorized`
- 启用 Prometheus 等:`micrometer-registry-prometheus` + `/actuator/prometheus`
- 监控指标:`resicache.cache.gets{result=hit|miss}`、`resicache.cache.puts`、`resicache.cache.evictions`(Spring Cache 标准指标前缀,具体名称以 Spring Data Redis 实际暴露为准)

## 与 [[early-expiration|早期过期]] 的观测

`EarlyExpirationSupport` 暴露两个补充观测点(不属于 Micrometer):

- `getThreadPoolStats()`:返回线程池统计字符串(活跃数 / 队列长度 / 完成任务数)
- `getRefreshingKeyCount()`:正在刷新的 key 数

适合写入自定义 `/actuator/info` 或业务日志,作为热 key 刷新压力的旁路信号。

## 相关

- [[auto-configuration]] —— 装配条件与降级路径
- [[configuration]] —— 无独立配置项(走 actuator 默认)
- [[cache-core]] —— `RedisProCacheWriter` 持 `CacheStatisticsCollector`
- [[early-expiration]] —— `getThreadPoolStats()` / `getRefreshingKeyCount()` 补充观测
