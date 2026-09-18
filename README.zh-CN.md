# ResiCache

**面向 Redis 的 Spring Cache 防护增强。** ResiCache 通过可编排的责任链，
为 Spring Cache 增加缓存穿透、缓存击穿、缓存雪崩和热点 key 提前刷新防护，
同时保留 Spring Cache 作为应用侧的使用模型。

[![CI](https://github.com/davidhlp/ResiCache/actions/workflows/ci.yml/badge.svg)](https://github.com/davidhlp/ResiCache/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[English](README.md) · [简体中文](README.zh-CN.md)

> [!WARNING]
> ResiCache 仍处于 1.0 之前（`v0.0.2`），不提供 SLA，目前由单人维护。
> 当前 `main` 构建线目标为 Spring Boot 4.0 与 Java 21，但尚未发布匹配的
> Maven Central 产物。正式采用前请先阅读[兼容性矩阵](COMPATIBILITY.md)和
> [已知限制](#已知限制)。

## 目录

- [概览](#概览)
- [功能特性](#功能特性)
- [兼容性与要求](#兼容性与要求)
- [快速开始](#快速开始)
- [工作原理](#工作原理)
- [配置](#配置)
- [扩展点](#扩展点)
- [运行时语义](#运行时语义)
- [方案对比](#方案对比)
- [已知限制](#已知限制)
- [不在范围内](#不在范围内)
- [项目结构](#项目结构)
- [开发与验证](#开发与验证)
- [项目状态与支持](#项目状态与支持)
- [安全](#安全)
- [许可证](#许可证)

## 概览

Spring Cache 提供了统一的缓存编程模型，但本身不负责缓存穿透、缓存击穿、
缓存雪崩或热点 key 过期防护。ResiCache 在 Spring Cache 与 Redis 之上，
将这些防护能力显式化、可组合化。

ResiCache 的设计目标是：

- **保持 Spring Cache 作为边界**：应用继续使用 `@EnableCaching`、缓存操作
  和 Spring 的缓存抽象。
- **让防护可组合**：每种机制都是一条有明确顺序的类型化 Handler，而不是
  分散在业务服务中的独立 AOP 逻辑。
- **关键边界默认失败关闭**：缺少分布式锁支持、不安全的序列化和非法配置都
  应当可观测，而不是静默地降低保证等级。
- **保持职责聚焦**：ResiCache 不是多级缓存、熔断器、限流器或 Reactive 缓存
  框架的替代品。

## 功能特性

| 能力 | 说明 |
|---|---|
| 布隆过滤器 | 拦截已知不存在的 key，降低缓存穿透 |
| 分布式锁 | 基于 Redisson，为缓存击穿提供并发加载协调 |
| TTL 抖动 | 随机化过期时间，减少缓存雪崩 |
| 空值缓存 | 显式开启后缓存负查询结果 |
| 提前过期 | 在正常过期边界前刷新热点 key |
| 可编排责任链 | 通过 `HandlerOrder` 与 `@HandlerPriority` 统一排列防护机制 |
| 安全序列化 | 使用受控的反序列化白名单和内部 wire envelope |
| Spring Cache 集成 | 复用 Spring Cache，而不是引入第二套 AOP 模型 |

`@RedisCacheable` 上的五类防护属性默认全部为 `false`；请根据应用的风险
模型显式开启需要的机制。

## 兼容性与要求

当前仓库只维护一条构建线：

| 组件 | 当前 `main` 构建线 |
|---|---:|
| Java | 21 |
| Spring Boot | 4.0.0 |
| Spring Framework / Spring Cache | 7.x，由 Boot 提供 |
| Spring Data Redis | 4.0.x |
| Redis Server | 7.x |
| Redisson | 3.50.0，可选 |
| Caffeine | 3.1.8，供内部能力使用 |
| Docker | Testcontainers 验证必需 |
| Testcontainers | 1.20.6，仅用于测试 |

完整版本矩阵和边界说明见 [COMPATIBILITY.md](COMPATIBILITY.md)。

### 已发布产物状态

当前 Boot 4 / Java 21 构建线以源码为主，尚未发布到 Maven Central。
`io.github.davidhlp:ResiCache:0.0.2` 是历史上的 Boot 3 / Java 17 产物，
不要把它当作当前 `main` 构建线的依赖。

如需试用当前构建线，请[从源码构建](#开发与验证)，或使用明确声明兼容性
构建线的正式 release。

## 快速开始

### 1. 在本地构建并安装当前源码

```bash
git clone https://github.com/davidhlp/ResiCache.git
cd ResiCache
./mvnw -Punit test -B
./mvnw install -DskipTests -B
```

第一条命令是不依赖 Docker 的贡献者检查；第二条命令会把当前源码按 POM
版本安装到本地 Maven 仓库，供本地消费者试用，不会发布产物。不要把这个本地
构建与 Maven Central 上历史版本的 `0.0.2` 混淆。

对于本地消费者应用，将当前检出版本的坐标加入该应用的 `pom.xml`：

```xml
<dependency>
    <groupId>io.github.davidhlp</groupId>
    <artifactId>ResiCache</artifactId>
    <version>0.0.2</version> <!-- 与本检出目录根 pom.xml 保持一致 -->
</dependency>
```

该依赖从本地 Maven 仓库解析。下面的配置和应用示例都应在这个消费者应用
中执行，而不是在 ResiCache 源码检出目录中执行。完整的 Redis 与 Redis
Cluster 验证命令见[开发与验证](#开发与验证)。

### 2. 配置 Redis

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      # timeout: 2s
resi-cache:
  redis:
    mode: single
    host: localhost
    port: 6379
    database: 0
    tls-enabled: false
```

`spring.data.redis.*` 与 `resi-cache.redis.*` 配置的是相互独立的客户端。
`spring.data.redis.*` 配置 ResiCache 缓存 I/O 使用的 Spring Data Redis
连接工厂。`resi-cache.redis.*` 配置分布式锁和同步能力使用的 Redisson
部署；`resi-cache.redisson.*` 控制其连接池、超时和重试设置。在 single
模式下，如果对应的 `resi-cache.redis.*` 值未设置，Redisson 可能回退使用
Spring Data Redis 的 host、port、database 和 password 值。使用 `sync=true`
时应保持最终生效的端点和凭据一致。

ResiCache 通过 Spring Boot 自动配置入口 `RedisCacheAutoConfiguration` 被发现。
它不会替应用添加 `@EnableCaching`；是否启用 Spring Cache 仍由应用负责。

### 3. 启用 Spring Cache

```java
@SpringBootApplication
@EnableCaching
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 4. 使用防护注解

```java
@Service
public class UserService {

    @RedisCacheable(
            value = "users",
            key = "#id",
            cacheNullValues = true,
            randomTtl = true,
            variance = 0.2,
            enableEarlyExpiration = true)
    public User getUserById(Long id) {
        return userRepository.findById(id);
    }
}
```

防护属性刻意要求显式开启。普通 `@Cacheable` 仍可与 Spring Cache 共存，
但在默认的 `nativeAnnotationMode=SELECTIVE` 模式下不会获得 ResiCache 防护：

```java
@Cacheable(value = "users", key = "#id")
public User getUserById(Long id) {
    // 走 Spring 原生缓存路径；需要防护时请使用 @RedisCacheable。
}
```

## 工作原理

### Handler 责任链

防护能力以责任链方式组装。执行顺序由 `HandlerOrder` 枚举统一定义，并通过
`@HandlerPriority` 绑定：

```text
┌─────────────────────────────────────────────────────────────┐
│                    CacheHandlerChain                        │
├─────────────────────────────────────────────────────────────┤
│  ① BloomFilter      (100) ── 布隆过滤器，防缓存穿透          │
│  ② SyncLock         (200) ── 分布式锁，防缓存击穿            │
│  ③ EarlyExpiration  (250) ── 提前过期，热点 key 保护         │
│  ④ TTL              (300) ── TTL 抖动，防缓存雪崩            │
│  ⑤ NullValue        (400) ── 空值缓存，防缓存穿透            │
│  ⑥ ActualCache      (500) ── 实际 Redis 写入                 │
└─────────────────────────────────────────────────────────────┘
```

每个 Handler 返回带有明确 `FlowControl` 决策（`CONTINUE`、`SKIP_ALL` 或
`TERMINATE`）的类型化 `HandlerResult`。Handler 可以通过
`requiresPostProcess` 与 `afterChainExecution` 参与后置处理；链引擎负责
推进主链，并隔离后置处理失败。

内置 Handler 由库的自动配置注册，只扫描库内部 runtime 包。应用自定义
Handler 必须由应用自己的组件扫描发现，或作为应用 Bean 提供；库的内部扫描
不会扫描宿主应用包。

### 防护边界

- **缓存穿透**：Bloom 缺少 membership bit 时会被视为确定 miss。默认实现只在缓存
  写入成功后回填，不会扫描或从数据源自动重建。对于过滤器中尚未存在的缓存 key，
  启用前必须通过公共 `BloomIFilter` seam 预填充或维护 membership set。
- **缓存击穿**：当 Redisson 或其他 `LockManager` 可用时，sync Handler 通过
  分布式锁协调并发加载。
- **缓存雪崩**：`randomTtl=true` 时，TTL 抖动会分散过期时间。
- **热点 key 过期**：显式开启后，提前过期机制会在正常过期边界前安排刷新。
- **负查询**：显式开启后，空值缓存可以保留不存在数据的结果。

## 配置

大多数配置使用 `resi-cache.*` 前缀，并绑定到
`RedisProCacheProperties`。配置在启动阶段解析；修改防护开关需要重启应用。

### 启用与防护开关

```yaml
resi-cache:
  enabled: true
  native-annotation-mode: SELECTIVE  # FULL | NONE | SELECTIVE
  protection:
    enabled: true
    bloom-filter-enabled: null        # null 继承 protection.enabled
    sync-lock-enabled: null
    early-expiration-enabled: null
    null-value-enabled: null
```

`resi-cache.enabled=false` 会关闭库的自动配置。`protection.enabled` 会关闭
布隆、同步锁、提前过期和空值防护，但保留基础 TTL 与实际缓存 Handler。
当总防护开关为 `false` 时，机制级 `true` 不能重新开启对应机制。

`native-annotation-mode` 控制 Spring 原生缓存注解：

- `SELECTIVE`（默认）：没有 ResiCache 注解的方法保留在 Spring 原生路径；
  存在 ResiCache 注解时，仅在对应 ResiCache operation 不存在时转换原生
  operation。混用或不对应的注解组合仍可能产生多个 operation 或 Advisor
  拦截；同一方法不要混用注解，除非已经验证实际结果。
- `FULL`：即使存在 ResiCache 注解，也转换所有受支持的 Spring 原生缓存
  注解；混用或不对应的注解可能产生多个 operation 或 Advisor 拦截，应测试
  这些组合。
- `NONE`：在 ResiCache operation source 中忽略 Spring 原生缓存注解。

### 全局配置

```yaml
resi-cache:
  default-ttl: 30m
  key-prefix: ""
  transaction-aware: false
```

### 指标与健康检查

指标默认选择性关闭，需要同时显式设置属性并提供可用的 `MeterRegistry`：

```yaml
resi-cache:
  metrics:
    enabled: true
```

Redis cache health indicator 使用同一个显式属性，并且需要 Spring Boot Actuator。

### 布隆过滤器

```yaml
resi-cache:
  bloom:
    prefix: "bf:"
    bit-size: 8388608
    hash-functions: 3
    hash-cache-size: 10000
```
这些配置绑定在 `resi-cache.bloom.*` 前缀下。

### 分布式锁

```yaml
resi-cache:
  sync-lock:
    timeout: 3000
    unit: MILLISECONDS
    prefix: "cache:lock:"
    local-only: false
```

`sync=true` 需要 Redisson 等分布式锁实现。当 `local-only=false`（默认）时，
缺少分布式支持会失败关闭，而不是静默声称具备多实例防护。只有在单 JVM 降级
明确可接受时，才应设为 `local-only=true`。

### 提前过期

```yaml
resi-cache:
  protection:
    early-expiration-enabled: true
  early-expiration:
    pool-size: 2
    max-pool-size: 10
    queue-capacity: 100
```

### Redis 部署方式

```yaml
resi-cache:
  redis:
    mode: single                  # single | cluster | sentinel
    host: localhost
    port: 6379
    database: 0
    tls-enabled: false
    # cluster-nodes: [host1:6379, host2:6379]
    # sentinel-master: mymaster
    # sentinel-nodes: [host1:26379]
```

部署配置会在绑定阶段校验与模式相关的字段。`resi-cache.redis.*` 是
Redisson 部署配置路径；`spring.data.redis.*` 配置缓存 I/O 使用的独立连接
工厂。在 single 模式下，如果对应的 `resi-cache.redis.*` 值未设置，Redisson
可能回退使用 Spring Data Redis 的 host、port、database 和 password 值。
`resi-cache.redisson.*` 控制 Redisson 连接池、超时和重试设置。使用
`sync=true` 时应保持最终生效的端点配置一致。生产环境凭据应交由应用的密钥
管理系统处理，不要写入提交的 README 示例。

### 序列化安全

```yaml
resi-cache:
  serializer:
    type-property: "@class"
    polymorphic-typing-enabled: false
    fail-on-unknown-type: true
    allowed-package-prefixes:
      - "io.github.davidhlp"
      - "com.example.*"
```

默认白名单是字面前缀 `io.github.davidhlp`，应用自己的业务包必须显式加入。
如需 dot 边界保护的子树匹配，请使用以 `.*` 结尾的前缀；例如
`com.example.*` 会匹配 `com.example.User` 与 `com.example.orders.Order`，但不会
匹配 `com.exampleX.User`。

### 按缓存覆盖配置

```yaml
resi-cache:
  caches:
    users:
      ttl: 10m
      cache-null-values: true
      key-prefix: "users:"
```

### `@RedisCacheable` 属性

| 属性 | 默认值 | 作用 |
|---|---:|---|
| `ttl` | `60` | 缓存 TTL，单位为秒 |
| `cacheNullValues` | `false` | 是否缓存 `null` 结果 |
| `useBloomFilter` | `false` | 是否启用布隆过滤防护 |
| `expectedInsertions` | `100000` | 布隆过滤器预期插入量 |
| `falseProbability` | `0.01` | 布隆过滤器误判率目标 |
| `randomTtl` | `false` | 是否启用 TTL 抖动 |
| `variance` | `0.2` | TTL 抖动幅度 |
| `enableEarlyExpiration` | `false` | 是否启用热点 key 提前刷新 |
| `earlyExpirationThreshold` | `0.3` | 触发刷新的剩余 TTL 比例 |
| `sync` / `syncTimeout` | `false` / `10` | 是否启用同步加载及等待超时 |

## 扩展点

### 注解族

公共注解族与 Spring Cache 操作相对应：

- `@RedisCacheable`：读穿缓存以及防护属性。
- `@RedisCachePut`：显式写入缓存。
- `@RedisCacheEvict`：移除缓存。
- `@RedisCaching`：在一个方法或类型上组合多个 ResiCache 操作。

`@RedisCaching` 可以在类型级别暴露操作，但其中组合注解的保护策略字段按
方法级别求值。仅有类型级声明不会把这些字段应用到未添加方法级注解的方法；
如果某个方法需要该策略，请在方法级别重复相关的 `@RedisCacheable`、
`@RedisCachePut` 或 `@RedisCacheEvict`。

### 自定义 Handler

自定义 Handler 实现公共 `CacheHandler` 契约，并使用带有 `HandlerOrder` 值的
`@HandlerPriority`。它必须被应用自己的组件扫描发现，或作为应用 Bean 提供。
Handler 返回类型化 `HandlerResult`，也可以参与后置处理，而不需要自行维护一个
指向下一个节点的链表。

只有文档明确声明的公共 seam 才适合替换。依赖公共类型或修改 Handler 行为前，
请先阅读 [STABILITY.md](STABILITY.md)。

## 运行时语义

### 缓存 I/O 失败行为

| 操作 | 行为 |
|---|---|
| GET | 降级为 cache miss，并记录内部失败 |
| PUT / PUT_IF_ABSENT / CLEAN | 抛出保留原始 cause 的类型化运行时异常 |
| REMOVE | 可观测的 best-effort 移除；移除失败不向上抛出 |
| `get(key, loader)` 且 loader 成功 | 即使写回失败也返回 loader 值；写回失败日志不包含原始 key |
| `get(key, loader)` 且 loader 失败 | 抛出 Spring 的 `Cache.ValueRetrievalException` |

`@CacheEvict(allEntries=true)` / CLEAN 是 best-effort、非原子操作，使用 SCAN 游标
和批量删除。Bloom bit 是独立维护的 membership set，默认由成功写入回填；不会从
数据源自动重建。清理缓存条目不会删除这些 bit。

### 序列化迁移

ResiCache 将值存储在内部 `{version, payload}` envelope 中，与 Spring 的
`GenericJackson2JsonRedisSerializer` 或 `JdkSerializer` 不兼容。已有应用不应
假定直接切换序列化器是安全的，应采用有边界的
shadow-read → dual-write → cutover 迁移流程。迁移指引见
[COMPATIBILITY.md](COMPATIBILITY.md)。

## 方案对比

ResiCache 刻意比通用缓存框架更聚焦：

| 能力 | JetCache | Caffeine | 裸 Redisson | **ResiCache** |
|---|:---:|:---:|:---:|:---:|
| 多级本地 + 远端缓存 | 有 | 仅本地 | — | — |
| 布隆过滤器 | — | — | 手写 | 有 |
| TTL 抖动 | — | — | 手写 | 有 |
| 分布式击穿锁 | — | — | 手写 | 有 |
| 空值缓存 | — | — | 手写 | 有 |
| 热点 key 提前刷新 | — | — | 手写 | 有 |
| 声明式防护责任链 | 部分 | — | — | 有 |
| 跨实例广播失效 | 有 | — | — | — |

JetCache 侧重多级缓存和跨实例广播失效；ResiCache 侧重面向 Redisson 的防护
责任链。两者解决缓存问题的不同部分，不是互相替代的同类方案。

## 已知限制

- **仍处于 1.0 之前**：小版本仍可能改变公共契约；依赖扩展点前请阅读
  [STABILITY.md](STABILITY.md)。
- **防护默认关闭**：`@RedisCacheable` 的五类防护属性默认都是 `false`。
- **Bloom membership 默认由写入回填**：不会扫描已有数据，缺少 bit 会短路 loader。
  对已有 key 启用 `useBloomFilter` 前，请先通过公共 `BloomIFilter` seam 预填充或维护。
- **当前构建线尚无产物**：Boot 4 / Java 21 构建线尚未发布到 Maven Central；
  `0.0.2` 是历史上的 Boot 3 / Java 17 构建线。
- **需要序列化迁移**：已有 Spring 原生序列化值不会自动兼容 ResiCache envelope。
- **不支持 Reactive 缓存**：WebFlux 的 `Mono` 与 `Flux` 方法不会进入阻塞式
  ResiCache interceptor。
- **异步缓存方法有限制**：`@Async` 方法不支持同步锁和布隆过滤增强。
- **防护开关只在启动时解析**：修改 `resi-cache.protection.*` 后必须重启应用。
- **Time-to-idle 读取**：底层 writer 读取路径刻意不在读时刷新 TTL，以避免额外
  写放大。

完整、经过测试的边界列表请以 [COMPATIBILITY.md](COMPATIBILITY.md) 为准。

## 不在范围内

ResiCache 刻意不实现以下更适合由专业组件负责的能力：

- **熔断与限流** → [Resilience4j](https://resilience4j.readthedocs.io/)
- **多级本地 + 远端缓存** → 使用 [Caffeine](https://github.com/ben-manes/caffeine) 作为本地层
- **Reactive 缓存** → 当前阻塞式 interceptor 不支持

## 项目结构

```text
ResiCache/
├── src/main/java/io/github/davidhlp/spring/cache/redis/
│   ├── annotation/          # 公共 ResiCache 注解
│   ├── cache/               # 内部 runtime、AOP、责任链、操作与装配
│   ├── chain/               # 稳定的 Handler、operation、result 契约
│   ├── config/              # 自动配置与 RedisProCacheProperties
│   ├── protection/          # 稳定的 BloomIFilter 与 LockManager seam
│   └── serialization/       # 序列化与迁移契约
├── src/test/java/            # 单元、契约与集成测试
├── resicache-bench/           # 独立 JMH 基准模块
├── scripts/ci/                # CI 与仓库守卫脚本
└── docs/adr/                  # 已接受的架构决策
```

## 开发与验证

### 前置条件

- JDK 21
- Maven 3.x，或仓库自带的 Maven Wrapper
- Docker：运行 Testcontainers Redis 与 Cluster 验证时必需

### 验证命令

```bash
# 不依赖 Docker 的日常路径
./mvnw -Punit test -B

# 完整 Redis/Testcontainers 验证
./mvnw clean verify -B

# 独立的代码风格与测试命名检查
./mvnw checkstyle:check -B
bash scripts/ci/check-test-names.sh

# 跳过测试打包
./mvnw clean package -DskipTests -B
```

`./mvnw clean verify -B` 强制 JaCoCo 覆盖率门槛：行覆盖率 70%，分支覆盖率
40%。完整开发与贡献流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 项目状态与支持

- **版本**：`v0.0.2`；1.0 之前只提供有限的语义化版本保证。
- **维护**：单人维护、无 SLA、尽力支持。
- **变更历史**：[CHANGELOG.md](CHANGELOG.md)
- **兼容性策略**：[COMPATIBILITY.md](COMPATIBILITY.md)
- **API 稳定性**：[STABILITY.md](STABILITY.md)
- **性能基线**：[PERFORMANCE.md](PERFORMANCE.md)
- **架构决策**：[ADR 索引](docs/adr/README.md)
- **贡献指南**：[CONTRIBUTING.md](CONTRIBUTING.md)

## 安全

疑似漏洞不要直接创建公开 issue，请按照 [SECURITY.md](SECURITY.md) 中的私有
报告流程提交。

## 许可证

[MIT License](LICENSE) © 2026 DavidHLP
