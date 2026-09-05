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

每个 Handler 通过返回包含明确控制决策（`FlowControl.CONTINUE`、`SKIP_ALL`、`TERMINATE`）的 `HandlerResult` 来控制责任链调度；需要后置回填或异步通知的 Handler 重写 `requiresPostProcess` 与 `afterChainExecution` 钩子。自定义 Handler 只需实现 `CacheHandler` 接口并标注 `@HandlerPriority`。

## 🚀 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.davidhlp</groupId>
    <artifactId>ResiCache</artifactId>
    <version>0.0.2</version>
</dependency>
```

> Maven Central 上的 `0.0.2` 是**早期 Boot 3 / Java 17 构建**（2026-09-05 已核实：Central 全部版本均属旧线）。当前 Boot 4 / Java 21 构建线尚未发布产物，上述坐标为规划坐标。

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

多数配置绑定到 `RedisProCacheProperties`；四个 `resi-cache.bloom.*` 实现参数由自动配置显式绑定，并由 additional metadata 描述。

### 总开关（当前未发布契约）

```yaml
resi-cache:
  enabled: true                 # 主开关；false 完全禁用 ResiCache
  protection:
    enabled: true               # false 跳过 bloom/lock/early-exp/null-value；TTL 保留
    bloom-filter-enabled: null  # 机制级覆盖，启动时静态解析：
    sync-lock-enabled: null     #   null 继承总开关；false 只关闭该机制；总开关为
    early-expiration-enabled: null # false 时分项 true 不能重新启用。修改配置需重启。
    null-value-enabled: null
```

防护开关**仅启动时生效**：责任链单例在首次构建时缓存；分项 `true` 无法覆盖总开关
`false`；修改防护配置需重启应用。TTL 与 ActualCache 始终保留。

### 全局配置

```yaml
resi-cache:
  default-ttl: 30m           # 默认 TTL
  key-prefix: ""             # 全局 key 前缀
  transaction-aware: false   # 事务感知缓存
```

### 布隆过滤器

```yaml
resi-cache:
  bloom:
    prefix: "bf:"
    bit-size: 8388608
    hash-functions: 3
    hash-cache-size: 10000
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
  protection:
    early-expiration-enabled: true  # 可选的机制级覆盖(仅启动时生效;null 继承总开关)
  early-expiration:
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
>
> **通配形式（当前未发布）**：以 `.*` 结尾的前缀被解释为通配符——匹配直接类（`com.example.Foo`）、所有子包内的类（`com.example.sub.Bar`、`com.example.foo.bar.baz.Qux` …），并以 dot 边界保护（`com.example.*` **不会**误匹配 `com.exampleX.Foo`）。当你想允许整个包子树、无需逐子包列出时使用。
>
> ```yaml
> resi-cache:
>   serializer:
>     allowed-package-prefixes:
>       - "io.github.davidhlp."
>       - "com.example.*"        # 一行搞定整棵 com.example 子树
> ```

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

> 注：5 大防护属性**默认全 `false`**——须在 `@RedisCacheable` 上逐个显式开启。`sync=true`（防击穿）依赖 Redisson 在 classpath,**缺失时 fail-fast**（拒绝静默降级为单 JVM 锁——跨实例无效）;确需单实例/测试降级,设 `resi-cache.sync-lock.local-only=true`。

### 每缓存覆盖（`caches.<name>`）

```yaml
resi-cache:
  caches:
    users:
      ttl: 10m
      cache-null-values: true
      key-prefix: "users:"
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

## 🆚 与 JetCache / Caffeine / 裸 Redisson 对比

在 Redis 之上做缓存,常见有四个选项:JetCache、Caffeine、裸 Redisson、
ResiCache。本项目定位:**ResiCache for Redisson — Redisson 忘了做的那条
可声明缓存防护链**。

| 能力 | JetCache | Caffeine | 裸 Redisson | **ResiCache** |
|------|:--------:|:--------:|:-----------:|:-------------:|
| 多级缓存(本地 + 远程) | ✅ | 仅本地 | — | — |
| 布隆过滤器(防穿透) | — | — | 手写 | ✅ |
| TTL 抖动(防雪崩) | — | — | 手写 | ✅ |
| 分布式击穿锁(防击穿) | — | — | 手写 | ✅ |
| null 值缓存 | — | — | 手写 | ✅ |
| 热点 Key 提前刷新 | — | — | 手写 | ✅ |
| 声明式 `@注解` 责任链 | 部分 | — | — | ✅ |
| 跨实例广播失效 | ✅ | — | — | — |

一句话结论:**JetCache 缺的那 3 项防护,以 Redisson-native 责任链补齐** —
布隆过滤器(防穿透)、TTL 抖动(防雪崩)、分布式击穿锁(防击穿)。
ResiCache 是补齐这 3 项空白的 Redisson 搭档;JetCache 主打多级缓存与
跨实例广播失效。两者**作用域互补,不是直接替代**。

## 📦 项目结构

```
src/main/java/io/github/davidhlp/spring/cache/redis/
├── annotation/          # @RedisCacheable, @RedisCacheEvict, @RedisCachePut, @RedisCaching
├── cache/               # package-private runtime：AOP、责任链、防护、操作、序列化与装配
├── chain/               # 稳定 CacheHandler/Operation/Result 契约与决策视图
├── config/              # 自动配置、RedisProCacheProperties 与 metrics 入口
├── protection/          # 稳定 BloomIFilter、LockManager、EarlyExpirationMode 契约
└── serialization/       # SerializationException、迁移 operator 契约与 wire envelope
```

## ⚠️ Known Limitations / 已知限制

v0.0.x 当前已知限制：

- **防护默认全关**：`@RedisCacheable` 的 5 大防护属性默认 `false`，须逐个显式开启
- **序列化信封与 Spring 原生不兼容**：ResiCache 用 `{version, payload}` 信封序列化，与 Spring 默认 `GenericJackson2JsonRedisSerializer` / `JdkSerializer` 不兼容——**存量项目接入需迁移**，否则全量缓存失效
- **序列化白名单默认锁作者包名**：`allowed-package-prefixes` 默认仅 `io.github.davidhlp.`，自定义类型须显式配置（见上文 [序列化安全](#序列化安全)）
- **双 Advisor 风险已消除**：`nativeAnnotationMode` 默认 `SELECTIVE`——纯 `@Cacheable` 完全走 Spring 原生、不被 ResiCache 接管。需要 FULL 兼容（接管 `@Cacheable`）可显式 `resi-cache.native-annotation-mode=FULL`
- **不支持 Reactive**（WebFlux / `Mono` / `Flux`）：`RedisCacheInterceptor` 是阻塞式，Reactive 方法不触发缓存
- **缓存 I/O 失败语义**：GET 降级为 miss 并记录日志；PUT、
  PUT_IF_ABSENT、CLEAN 抛出带原始 cause 的类型化运行时异常；
  REMOVE 为可观测 best-effort，不抛异常。**read-through（`get(key, loader)`）可用性优先**：
  loader 成功值必定返回——写回失败仅记录（脱敏）日志、不覆盖该值；
  loader 失败仍以 Spring `Cache.ValueRetrievalException` 呈现。
- **`@CacheEvict(allEntries=true)`（CLEAN）是 best-effort、非原子**：与 Spring 原生 `RedisCache.clear`/`DefaultRedisCacheWriter.clean` 一致，用 SCAN 游标 + 批量 UNLINK/DEL，CLEAN 期间新写入的 key 可能被遗漏，大 key 集时缓存短暂处于半删状态。刻意不用 Lua/MULTI 原子化（Redis 单线程 O(keyspace) 阻塞、Cluster cross-slot）。Bloom 表示数据源可能存在；CLEAN 只清缓存、保留旧 bits，因此只允许 false-positive，不会制造阻止 loader 的 false-negative。

## 🚫 Not in Scope

ResiCache **刻意不做**以下能力，避免过度膨胀——请用专业工具配合：

- **熔断 / 限流** → 配 [Resilience4j](https://resilience4j.readthedocs.io/) 保护下游
- **多级本地 + 远端缓存** → 配 [Caffeine](https://github.com/ben-manes/caffeine) 做本地层
- **Reactive 缓存**（WebFlux）→ 不支持

## 🔧 依赖版本

| 依赖 | 版本 |
|------|------|
| Spring Boot | 4.0.0（parent） |
| Java | 21 |
| Redisson | 3.50.0（optional） |
| Caffeine | 3.1.8 |
| Testcontainers | 1.20.6 |

完整兼容矩阵见 [COMPATIBILITY.md](COMPATIBILITY.md)。

## 项目状态与维护

- **版本**：v0.0.2 — 语义化版本 < 1.0，API 可能在 minor 版本变更，breaking 项在 [CHANGELOG.md](CHANGELOG.md) 标 ⚠️
- **维护**：单人维护（[DavidHLP](https://github.com/davidhlp)），**Non-SLA best-effort**——不承诺响应时间，但积极修复 issue
- **贡献指南**：欢迎 PR，流程见 [CONTRIBUTING.md](CONTRIBUTING.md)
- **性能实测基准**：JMH 实测数据与 SLO 达标表见 [PERFORMANCE.md](PERFORMANCE.md)
- **稳定性契约**：0.x 周期兼容与 1.0 毕业指标见 [STABILITY.md](STABILITY.md)
- **兼容性矩阵**：支持的 Spring Boot / Java / Redisson 版本见 [COMPATIBILITY.md](COMPATIBILITY.md)
- **安全策略**：漏洞私有报告流程见 [SECURITY.md](SECURITY.md)

## 📄 License

[MIT License](LICENSE) © 2026 DavidHLP
