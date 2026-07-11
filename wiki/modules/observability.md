---
title: 健康检查与可观测性
type: modules
tags:
  - module
  - HealthIndicator
  - actuator
  - MeterRegistry
  - chain
  - MDC
  - 健康检查
related: [auto-configuration, configuration, cache-core, early-expiration, serialization, chain-of-responsibility]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/health/RedisCacheHealthIndicator.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/MetricsAutoConfiguration.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/SerializerWhitelistStartupGuard.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/CacheHandlerChain.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/AbstractCacheHandler.java
status: stable
created: 2026-06-21
updated: 2026-07-11
---

# 健康检查与可观测性

ResiCache 走 Spring Boot actuator 既有体系,不发明独立指标框架。

> 独立 `CacheMetricsRecorder` 类在 `a5ab55b refactor` 后已不存在——指标在使用点就地记录,健康检查由 `RedisCacheHealthIndicator` 提供。

## 三个组件

### RedisCacheHealthIndicator — `RedisCacheHealthIndicator`

`@ConditionalOnClass(HealthIndicator.class)`,在 `/actuator/health` 暴露 ResiCache 整体健康(Redis 连通性、关键 bean 装配)。actuator 不在 classpath 时该 bean 不创建。

### MetricsAutoConfiguration — `MetricsAutoConfiguration`

`@ConditionalOnClass({MeterRegistry.class, HealthIndicator.class})`,仅当 Micrometer + actuator 同在时装配指标桥接与健康检查 bean。两者缺一即跳过(零配置自动降级)。

### CacheStatisticsCollector(Spring 标准统计)

`RedisProCacheWriter` 注入 Spring Data Redis 的 `CacheStatisticsCollector`,各 handler 在关键路径累计命中 / 未命中:

- [[bloom-filter]] `handleGet`:布隆拒绝时 `statistics.incMisses(cacheName)`
- [[early-expiration]] 同步刷新:`statistics.incMisses(cacheName)`
- `RedisProCacheWriter` 自身:GET / PUT 命中与未命中

经 `CacheStatisticsCollector` 暴露到 actuator 标准 `cache` 端点;`MeterRegistry` 在时由 `MetricsAutoConfiguration` 桥接为时序指标。

## 指标如何产生

无中央 recorder,指标在**使用点就地记录**:

```
请求 ──▶ BloomFilterHandler.handleGet ─布隆拒绝─▶ statistics.incMisses(cacheName)
       EarlyExpirationHandler 同步刷新 ─────────▶ statistics.incMisses(cacheName)
```

设计意图:**统计职责留在发生点**,避免中央 recorder 与 handler 耦合。

## 责任链执行可观测性

责任链执行提供三层 runtime 信号(装配由 `CacheHandlerChainFactory` + `ChainObserver` 统一收口,Observer 4 钩子 `onChainStart`/`beforeNode`/`afterNode`/`onChainEnd`):

| 信号 | 类型 | 说明 |
|---|---|---|
| `resicache.chain.execute` | Micrometer Timer | 整条链 full lifecycle(head handle + post-process) |
| `resicache.handler.fired` | Counter(tag `handler`) | 各 handler 被引擎求值频率,cardinality bounded(~6) |
| `[chain]` DEBUG + MDC `requestId` | 日志 | 一次 GET/PUT 内所有 handler 共享 requestId,串联决策序列 |

- DEBUG 关闭时静默 no-op;requestId 用 `ThreadLocalRandom`(热路径规避 SecureRandom 熵竞争)。
- MDC 用 snapshot/restore,finally 恢复调用方原值,**不**用 `MDC.clear()` 误清宿主线程其它 MDC(如 `traceId`)。
- 语义 counter(`ttl.jittered` / `null.hit` / `sync.lock.acquired` / `bloom.blocked` / `early-refresh.triggered`)由 `AbstractCacheHandler#onAttachMetrics` 钩子统一注册,disabled handler 不注册(与 `fired` 行为对齐)。

## 前置条件与降级

| 依赖 | 缺时行为 |
|---|---|
| 不引入 `spring-boot-starter-actuator` | 无 `/actuator/health`、无 `RedisCacheHealthIndicator` bean |
| 不引入 Micrometer | 无 `MeterRegistry`,`MetricsAutoConfiguration` 跳过;`ObjectProvider.getIfAvailable()` 优雅降级(见 [[auto-configuration]]) |
| 只缺其一 | 该 bean 不创建,其他仍生效(各 `@ConditionalOnClass` 独立判定) |

`@ConditionalOnClass` + `ObjectProvider` 保证:生产全量 / 测试精简环境都能启动。

## 生产部署建议

- 启用 actuator:`spring-boot-starter-actuator`
- 暴露 health:`management.endpoint.health.show-details=when-authorized`
- 启用 Prometheus:`micrometer-registry-prometheus` + `/actuator/prometheus`
- 监控指标:`resicache.cache.gets{result=hit|miss}` / `resicache.cache.puts` / `resicache.cache.evictions`(Spring Cache 标准前缀,具体名以 Spring Data Redis 实际暴露为准)+ `resicache.chain.execute` / `resicache.handler.fired`

## 与 [[early-expiration|早期过期]] 的观测

`ThreadPoolEarlyExpirationExecutor`(ADR-0012 删 `EarlyExpirationSupport` 门面后由 handler 直持)暴露两个旁路观测点(非 Micrometer):

- `getStats()` — 线程池统计(活跃数 / 队列长度 / 完成任务数)
- `getActiveCount()` — 正在刷新的 key 数

适合写入自定义 `/actuator/info` 或业务日志,作为热 key 刷新压力信号。

## 启动期 misconfig 告警(loud-startup)

不走 Micrometer,启动时对关键 misconfig 直接发 WARN——第一次启动即可见,不必等首次 cache miss 失败:

- **`SerializerWhitelistStartupGuard`**([[serialization]]):`@EventListener(ApplicationReadyEvent.class)` 检查 `resi-cache.serializer.allowed-package-prefixes` 空 → WARN 提示补 host app root package。防御"为宽松清空白名单 → 非 framework type 反序列化抛异常"。
- **`SyncLockProperties.localOnly`**([[breakdown-lock]]):配 `sync=true` 但无 Redisson → WARN `protection.degraded=local-only`。防御"多实例误以为配了分布式锁,实际单 JVM synchronized"。

> 两条 startup WARN 各自独立,都是 misconfig 防御:把昂贵的 runtime 失败提前到零成本 startup 日志检查。

## 相关

- [[auto-configuration]] — 装配条件与降级路径
- [[configuration]] — `SerializerWhitelistStartupGuard` 装配上下文
- [[cache-core]] — `RedisProCacheWriter` 持 `CacheStatisticsCollector`
- [[early-expiration]] — `getStats()` / `getActiveCount()` 补充观测
- [[serialization]] — `SerializerWhitelistStartupGuard` WARN 触发条件
