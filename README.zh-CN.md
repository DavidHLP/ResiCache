# ResiCache

> ⚠️ **本中文版可能滞后，请以 [英文 README](README.md) 为准（canonical / source of truth）。**

**Spring Cache 的防护增强注解生态** —— 在 `@Cacheable` 之外，用 `@RedisCacheable` 一行注解为 Redis 缓存补齐防穿透 / 防击穿 / 防雪崩 / 热 key 早刷新能力，通过可编排的责任链注入防护，不重造 AOP。

[![CI](https://github.com/davidhlp/ResiCache/actions/workflows/ci.yml/badge.svg)](https://github.com/davidhlp/ResiCache/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **项目状态：早期（v0.0.2）· Non-SLA best-effort · 单人维护**
> 生产采用前请务必先读 [⚠️ Known Limitations](#-known-limitations--已知限制)。

## 这是什么

Spring Cache（`@Cacheable` / `@CachePut` / `@CacheEvict`）只解决"缓存"，不解决"防护"——缓存穿透、击穿、雪崩、热 key 过期都要业务自己补。ResiCache 用一套 **`@RedisCacheable` 增强注解** + **可编排的责任链**，把这些防护变成声明式能力：

- **与 Spring Cache 共存**：继承 `RedisCacheManager` / `CacheInterceptor`，不替换 `@EnableCaching`，不重造 AOP
- **与 JetCache 的差异**：JetCache 主打**多级缓存**，ResiCache 主打**缓存防护纵深**——责任链上每个 handler 可插拔、可编排，这是 JetCache 做不到的

## 📋 功能特性

| 特性 | 说明 |
|------|------|
| **布隆过滤器** | 防缓存穿透，拦截不存在的 key |
| **分布式锁** | 基于 Redisson，防缓存击穿（**需 Redisson 在 classpath**） |
| **TTL 抖动** | 随机化 TTL，防缓存雪崩 |
| **空值缓存** | 缓存 null，防穿透 |
| **提前过期** | 异步提前刷新热 key，提升命中率 |
| **可编排责任链** | handler 按优先级串接，支持自定义插队（差异化能力） |
| **安全序列化** | 白名单反序列化，防 Jackson 多态类型攻击 |

> ResiCache **不提供** 熔断 / 限流 / 多级本地缓存 / Reactive 支持，见 [🚫 Not in Scope](#-not-in-scope)。

## 🏗️ 架构设计

ResiCache 采用 **责任链模式** 实现缓存写入防护。处理器顺序由 `HandlerOrder` 枚举统一定义，通过 `@HandlerPriority` 绑定：

```
┌─────────────────────────────────────────────────────────────┐
│                    CacheHandlerChain                        │
├─────────────────────────────────────────────────────────────┤
│  ① BloomFilter      (100) ── 布隆过滤器，防缓存穿透          │
│  ② SyncLock         (200) ── 分布式锁，防缓存击穿            │
│  ③ EarlyExpiration  (250) ── 提前过期，热 key 保护           │
│  ④ TTL              (300) ── TTL 抖动，防缓存雪崩            │
│  ⑤ NullValue        (400) ── 空值缓存，防穿透                │
│  ⑥ ActualCache      (500) ── 实际 Redis 写入                 │
└─────────────────────────────────────────────────────────────┘
```

任一 handler 可设置 `output.skipRemaining=true` 短路后续链路；`PostProcessHandler` 在链结束后回调。第三方 handler 可通过扩展 `HandlerOrder` 枚举插队。

## 🚀 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.davidhlp</groupId>
    <artifactId>ResiCache</artifactId>
    <version>0.0.2</version>
</dependency>
```

### 2. 配置 Redis

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

> ResiCache 通过 Spring Boot 自动装配生效（入口 `RedisCacheAutoConfiguration`，见 `META-INF/spring/...AutoConfiguration.imports`），无需额外 `@EnableXxx`。

### 3. 启用缓存

```java
@SpringBootApplication
@EnableCaching
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 4. 使用注解

**推荐：`@RedisCacheable`（防护入口）**

```java
@Service
public class UserService {
    @RedisCacheable(value = "users", key = "#id",
                    useBloomFilter = true,        // 布隆过滤防穿透
                    cacheNullValues = true,       // 空值缓存
                    randomTtl = true,              // TTL 抖动防雪崩
                    variance = 0.2,                // 抖动幅度 ±20%
                    enableEarlyExpiration = true)  // 热 key 提前刷新
    public User getUserById(Long id) {
        return userRepository.findById(id);
    }
}
```

**兼容：`@Cacheable`（不增强防护）**

```java
@Cacheable(value = "users", key = "#id")  // 可共存，但不获得防护
public User getUserById(Long id) { ... }
```

> `@Cacheable` 可与 ResiCache 共存，但**不获得防护**——防护属性（`useBloomFilter` / `randomTtl` / ...）仅在 `@RedisCacheable` 上。要防护请用 `@RedisCacheable`。

## ⚙️ 配置选项

所有配置前缀为 `resi-cache.*`（绑定 `RedisProCacheProperties`）。

### 全局配置

```yaml
resi-cache:
  default-ttl: 30m           # 默认 TTL
  key-prefix: ""             # 全局 key 前缀
  transaction-aware: false   # 事务感知缓存
  fail-on-spel-error: true   # SpEL 求值失败是否抛异常
```

### 布隆过滤器

```yaml
resi-cache:
  bloom-filter:
    enabled: true
    expected-insertions: 100000   # 预期插入量
    false-probability: 0.01       # 期望误判率
    hash-cache-size: 10000        # 本地哈希缓存条目数
    rebuild-window-seconds: 30    # CLEAN 后布隆重建窗口(秒);0=禁用(旧行为)
```

### 分布式锁

```yaml
resi-cache:
  sync-lock:
    timeout: 3000
    unit: MILLISECONDS
    prefix: "cache:lock:"
    local-only: false   # true = 无 Redisson 时显式单 JVM 降级(否则 fail-fast)
```

### 提前过期（热 key 保护）

```yaml
resi-cache:
  early-expiration:
    enabled: true
    pool-size: 2           # 核心线程数
    max-pool-size: 10      # 最大线程数
    queue-capacity: 100    # 队列容量
```

### Redis 部署

```yaml
resi-cache:
  redis:
    mode: single           # single | cluster | sentinel
    host: localhost
    port: 6379
    database: 0
    tls-enabled: false
    # cluster-nodes: [host1:6379, host2:6379]
    # sentinel-master: mymaster
    # sentinel-nodes: [host1:26379, host2:26379]
```

### 序列化安全

```yaml
resi-cache:
  serializer:
    type-property: "@class"                    # Jackson 类型标签
    polymorphic-typing-enabled: false          # 默认关闭，更安全
    fail-on-unknown-type: true                 # 未知类型即失败
    allowed-package-prefixes:                  # 反序列化白名单
      - "io.github.davidhlp."
      - "com.example."                          # ← 务必加上你自己的业务包名
```

> ⚠️ **白名单默认仅含 `io.github.davidhlp.`**。缓存自定义业务类型（如 `com.example.User`）时，**必须**在 `allowed-package-prefixes` 显式添加你的包名，否则反序列化会抛异常。

### 注解级属性（`@RedisCacheable`）

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `ttl` | 60 | 缓存 TTL（秒） |
| `cacheNullValues` | false | 是否缓存 null |
| `useBloomFilter` | false | 启用布隆过滤 |
| `expectedInsertions` | 10000 | 布隆预期插入量 |
| `falseProbability` | 0.03 | 布隆误判率 |
| `randomTtl` | false | 启用 TTL 抖动 |
| `variance` | 0.2 | TTL 抖动幅度 |
| `enableEarlyExpiration` | false | 启用提前过期 |
| `earlyExpirationThreshold` | 0.3 | 提前过期阈值（TTL 剩余比例） |
| `sync` / `syncTimeout` | false / 10 | 同步等待与超时 |

> 注：5 大防护属性**默认全 `false`**——须在 `@RedisCacheable` 上逐个显式开启。`sync=true`（防击穿）依赖 Redisson 在 classpath,**缺失时 fail-fast**（拒绝静默降级为单 JVM 锁——跨实例无效）;确需单实例/测试降级,设 `resi-cache.sync-lock.local-only=true`。v0.2.0 将引入 `resi-cache.protection.preset` 简化批量开启。

### 每缓存覆盖（`caches.<name>`）

```yaml
resi-cache:
  caches:
    users:
      ttl: 10m
      cache-null-values: true
      enable-bloom-filter: true
      enable-early-expiration: false
```

## 📖 工作原理

### 缓存穿透防护

布隆过滤器在缓存层之前拦截不存在的数据请求：

```
请求 ──→ BloomFilter ──→ 存在？──→ 是 ──→ 继续执行
                     │
                     └──→ 否 ──→ 直接返回 null（不查缓存）
```

### 缓存击穿防护

分布式锁确保同一时刻只有一个请求去加载数据：

```
请求A ──→ 获取锁 ──→ 查 DB ──→ 写入缓存 ──→ 释放锁
请求B ──→ 获取锁 ──→ 已存在，直接从缓存获取
```

### 缓存雪崩防护

TTL 随机化避免大量缓存同时过期：

```
设置 TTL = baseTtl ± variance × baseTtl   （randomTtl=true 时生效）
```

## 📦 项目结构

```
src/main/java/io/github/davidhlp/spring/cache/redis/
├── annotation/          # @RedisCacheable, @RedisCacheEvict, @RedisCachePut, @RedisCaching
├── cache/               # RedisProCache, RedisProCacheManager, RedisProCacheWriter, RedisCacheInterceptor
├── chain/               # 责任链：CacheHandler/Chain/Factory + AbstractCacheHandler + ActualCacheHandler
│   └── model/           #   CacheInput(不可变) / CacheOutput(可变) / CacheContext
├── config/              # 自动配置 + RedisProCacheProperties + SecureJackson
├── protection/          # 五大防护机制
│   ├── avalanche/       #   TtlHandler (300) ── 防雪崩
│   ├── bloom/           #   BloomFilterHandler (100) + filter/(Local/Redis/Hierarchical)
│   ├── breakdown/       #   SyncLockHandler (200) + DistributedLockManager ── 防击穿
│   ├── nullvalue/       #   NullValueHandler (400) ── 防穿透
│   └── refresh/         #   EarlyExpirationHandler (250) ── 热 key 保护
├── operation/           # RedisCacheable/Put/Evict Operation + RedisCacheRegister
├── factory/             # OperationFactory + 具体工厂
├── handler/             # AnnotationHandler + 注解处理器
├── eviction/            # TwoListLRU + TwoListEvictionStrategy
├── serialization/       # SecureJackson / SecureNullValueDeserializer（安全反序列化）
├── observability/       # RedisCacheHealthIndicator
└── holder/              # CacheOperationMetadataHolder
```

## ⚠️ Known Limitations / 已知限制

v0.0.2 当前已知的硬限制（均在 [Roadmap](#-roadmap) 中修复）：

- **防护默认全关**：`@RedisCacheable` 的 5 大防护属性默认 `false`，须逐个显式开启 → v0.2.0 引入 `resi-cache.protection.preset=STRICT/STANDARD/NONE`
- **序列化信封与 Spring 原生不兼容**：ResiCache 用 `{version, payload}` 信封序列化，与 Spring 默认 `GenericJackson2JsonRedisSerializer` / `JdkSerializer` 不兼容——**存量项目接入需迁移**，否则全量缓存失效 → v0.2.0 提供 `shadow → dual-write → cutover` 迁移工具
- **序列化白名单默认锁作者包名**：`allowed-package-prefixes` 默认仅 `io.github.davidhlp.`，自定义类型须显式配置（见上文 [序列化安全](#序列化安全)）
- **双 Advisor 风险已消除（v0.0.3）**：`nativeAnnotationMode` 默认已改为 `SELECTIVE`——纯 `@Cacheable` 完全走 Spring 原生、不被 ResiCache 接管，双 Advisor 风险随之消除。需要 FULL 兼容（接管 `@Cacheable`）可显式 `resi-cache.native-annotation-mode=FULL`
- **不支持 Reactive**（WebFlux / `Mono` / `Flux`）：`RedisCacheInterceptor` 是阻塞式，Reactive 方法不触发缓存
- **`@CacheEvict(allEntries=true)`（CLEAN）是 best-effort、非原子**：与 Spring 原生 `RedisCache.clear`/`DefaultRedisCacheWriter.clean` 一致，用 SCAN 游标 + 批量 UNLINK/DEL，CLEAN 期间新写入的 key 可能被遗漏，大 key 集时缓存短暂处于半删状态。刻意不用 Lua/MULTI 原子化（Redis 单线程 O(keyspace) 阻塞、Cluster cross-slot）。启用布隆时，`rebuild-window-seconds` 窗口（v0.1.0）防止擦除后重建期的静默 null。
- **暂无 JMH 基准**：性能数据待 v0.3.0 补齐

## 🚫 Not in Scope

ResiCache **刻意不做**以下能力，避免过度膨胀——请用专业工具配合：

- **熔断 / 限流** → 配 [Resilience4j](https://resilience4j.readthedocs.io/) 保护下游
- **多级本地 + 远端缓存** → 配 [Caffeine](https://github.com/ben-manes/caffeine) 做本地层
- **Reactive 缓存**（WebFlux）→ 暂不支持，计划在路线图评估

## 🗺️ Roadmap

| 版本 | 重点 | 状态 |
|---|---|---|
| **v0.0.3** | 文档诚实化 + `resi-cache.enabled` kill-switch + Reactive 显式排除 + 双 Advisor 修复 | 进行中 |
| **v0.1.0** | Boot 4.0 / SDR 4.0 / Java 21 单构建（FIRE M0–M4 ✅ `38c514a`）+ WS-1.2 P0 硬化（fail-fast sync / Cluster hash-tag / 原子 CLEAN 重建窗口 ✅ `5a05d0a`）+ WS-1.3 Path C 销毁 ThreadLocal（7 步序列 ✅ `a42a1c1`/`ceb3901`/`a483de9`/`b377c16`/`b9d6b40`/`cf4e2b1`）+ 首次发 Maven Central | 待发（发版卡在 `OSSRH_*` → `MAVEN_USERNAME`/`MAVEN_PASSWORD` secret 对齐） |
| **v0.2.0** | `protection.preset` + 序列化兼容 + 迁移工具（同一发布单元） | 计划 |
| **v0.3.0** | JMH 基准 + 可观测性（per-handler Micrometer tag） | 计划 |
| **v1.0.0** | API 冻结 + 正式推广（sample 项目 / 对比页 / 文章） | 计划 |

详见 [CHANGELOG.md](CHANGELOG.md)。

## 🔧 依赖版本

| 依赖 | 版本 |
|------|------|
| Spring Boot | 3.4.13（parent） |
| Java | 17+ |
| Redisson | 3.27.0（optional） |
| Caffeine | 3.1.8 |
| Testcontainers | 1.20.4（CI Docker 兼容覆盖） |

完整兼容矩阵见 [COMPATIBILITY.md](COMPATIBILITY.md)。

## 项目状态与维护

- **版本**：v0.0.2 — 语义化版本 < 1.0，API 可能在 minor 版本变更，breaking 项在 [CHANGELOG.md](CHANGELOG.md) 标 ⚠️
- **维护**：单人维护（[DavidHLP](https://github.com/davidhlp)），**Non-SLA best-effort**——不承诺响应时间，但积极修复 issue
- **贡献**：欢迎 PR，流程见 [CONTRIBUTING.md](CONTRIBUTING.md)
- **安全漏洞**：请私有报告，见 [SECURITY.md](SECURITY.md)
- **兼容矩阵**：见 [COMPATIBILITY.md](COMPATIBILITY.md)

## 📄 License

[MIT License](LICENSE) © 2026 DavidHLP
