---
title: 可观测性(observability)
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
  - src/main/java/io/github/davidhlp/spring/cache/redis/observability/RedisCacheHealthIndicator.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/MetricsAutoConfiguration.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/SerializerWhitelistStartupGuard.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/CacheHandlerChain.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/AbstractCacheHandler.java
status: stable
created: 2026-06-21
updated: 2026-06-29
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

## 责任链执行可观测性(chain execution observability)

除 handler 内就地统计外,责任链执行本身提供两个独立的 runtime 观测信号(见 [[chain-of-responsponsibility]]):

### `resicache.chain.execute` Micrometer Timer(WS-1.4)

`CacheHandlerChain` 构造时(仅 `MeterRegistry` 在 classpath)注册单个 Timer `resicache.chain.execute`,记录**整条链的 full lifecycle**(head handle + post-process)。`ObjectProvider<MeterRegistry>` 允许 registry 缺失(测试 stub / 无 actuator 环境)—— 缺时计时静默 no-op,行为不变。本 tick 为单 Timer(无 tags);per-cacheName / per-operation tags 与 per-handler Micrometer tags 留后续 release(guide §223,line 291 移至 v0.1.0)。

### Per-handler `[chain]` DEBUG 日志 + MDC requestId 关联(R24)

`CacheHandlerChain.execute(CacheContext)` 每次执行 stamp 一个 `requestId` 进 SLF4J `MDC`(`CacheHandlerChain.MDC_REQUEST_ID_KEY = "requestId"`),`AbstractCacheHandler.handle` 在**单点 chain-advance** 处对每个被引擎求值的 handler 记录:

```
[chain] handler=BloomFilterHandler decision=CONTINUE key=resicache:users:42 requestId=7f3a9c1b2e0d4
```

- **关联性**:一次 GET/PUT 内所有 handler 的 DEBUG 行共享同一 `requestId`,可在日志中按 `requestId` 串联整条链的决策序列(guide §223d / line 388 / line 248「单 requestId 串所有 handler + decision」契约)。这是后续 hot-key auto-refresh / adaptive TTL 等「non-deterministic 厚化」的前置可观测性前提。
- **快照/恢复**:execute 用 snapshot/restore —— 只动自己的 key,finally 恢复调用方原值,**不**用 `MDC.clear()` 误清宿主线程其它 MDC(如 `traceId`),与 `RedisProCacheWriter` 异步路径的防御式 MDC 风格一致。
- **requestId 生成**:用 `ThreadLocalRandom` 而非 `UUID.randomUUID()` —— execute 是缓存热路径(每次 GET/PUT 必经),规避 `SecureRandom` 熵竞争 / 潜在阻塞;64-bit 无符号十六进制对日志关联已足够。
- **降级**:DEBUG 关闭时 `log.debug` 静默 no-op(SLF4J 标准门控);`requestId` 仍 stamp(为后续 Observation span 关联预留)。
- **scope**:guide §223 per-handler observability 大项。(d) DEBUG+MDC requestId 见上(R24);**per-handler `resicache.handler.fired` counter 见下(R25)**;(e) Observation spans、Micrometer handler/decision tags 留后续轮次。

### `resicache.handler.fired` per-handler 计数(R25)

`AbstractCacheHandler.handle()` 单点为每个被引擎求值的 handler 自增 `resicache.handler.fired` counter(tag `handler` = 运行时子类 SimpleName,如 `BloomFilterHandler`)。`MeterRegistry` 由 `CacheHandlerChainFactory` 建链时注入每个 enabled `AbstractCacheHandler`(方法 `attachMeterRegistry`,幂等;registry 缺失时 counter 为 null,no-op)。

- **cardinality**:`handler` tag 数 = handler 数(bounded ~6),**不加** `redisKey`(unbounded,见 guide line 261)。
- **查询示例**:`rate(resicache.handler.fired{handler="SyncLockHandler"}[5m])` = 该 handler 被引擎求值的频率 —— 回答「哪个保护机制在 fire、fire 多频繁」。
- **与 R24 DEBUG 的关系**:counter = 结构化指标(告警 / Grafana 仪表盘),DEBUG log = 详细决策序列(哪个 key、什么 decision);两者互补,均为 guide §223 per-handler observability 的组成。
- **新 metric 名**:`resicache.handler.fired` 是 pre-1.0 新增 metric(STABILITY §2 metric namespace may-change),additive,不替换既有 `resicache.handler.null.hit` / lock counter 等语义 counter。

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
- [[configuration]] —— 无独立配置项(走 actuator 默认)+ `SerializerWhitelistStartupGuard` 启动期守卫装配上下文
- [[cache-core]] —— `RedisProCacheWriter` 持 `CacheStatisticsCollector`
- [[early-expiration]] —— `getThreadPoolStats()` / `getRefreshingKeyCount()` 补充观测
- [[serialization]] —— `SerializerWhitelistStartupGuard` 详细 WARN 触发条件与提示内容

## 启动期 misconfig 告警(loud-startup observability)

不走 Micrometer,直接在应用启动时就关键 misconfig 发 WARN 日志 — 部署后第一次启动就能在 `stdout` 看到,不必等到第一次 cache miss 失败才暴露。两条独立 startup 守卫:

### `SerializerWhitelistStartupGuard`(R15)— [[serialization]]

`@EventListener(ApplicationReadyEvent.class)` 触发,检查 `resi-cache.serializer.allowed-package-prefixes` 是否为 `null` 或 `[]`,若空则发 WARN,提示用户补回 host app root package(`com.example.*` 通配 / `com.example.dto` literal 两种写法),并重申默认 `[io.github.davidhlp]` 仅覆盖 framework 内部 types。详见 [[configuration]] 与 [[serialization]]。

防御场景:**用户为「宽松」清空白名单** → 所有非 framework 内部 type 反序列化抛 `SerializationException`;启动期即可在日志看到,不必等到首次 cache miss。

### `SyncLockProperties.localOnly` 启动期告警(早期)— [[breakdown-lock]]

`RedisProCacheConfiguration` 装配 `sync=true` 但无分布式锁后端(Redisson 缺失 → 无 `LockManager` bean)时,启动期发 `protection.degraded=local-only` WARN 提示。这是 pre-existing 行为(早于 R15),由 `SyncLockProperties.localOnly` 字段控制是否显式接受单 JVM 降级(默认 `false` = 默认 fail-fast,见 [[breakdown-lock]])。

防御场景:**多实例部署误以为配置了分布式锁** → 实际是单 JVM synchronized,无法跨实例防击穿。

> 这两条 startup WARN 各自独立,都是 misconfig 防御;不是重复,不是互相替代。`SerializerWhitelistStartupGuard` 守「序列化安全门」,`localOnly` 告警守「分布式锁一致性」。两者都是「在 runtime 第一次失败前先在启动期 hint」的设计哲学 — 把昂贵的 runtime 失败提前到零成本的 startup 日志检查。
