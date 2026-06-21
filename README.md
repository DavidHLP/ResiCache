# ResiCache

基于 Spring Cache 和 Redis 的高性能缓存防护工具包。

[![CI](https://github.com/davidhlp/ResiCache/actions/workflows/ci.yml/badge.svg)](https://github.com/davidhlp/ResiCache/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## 📋 功能特性

| 特性 | 说明 |
|------|------|
| **布隆过滤器** | 防止缓存穿透，自动将不存在的数据拦截在缓存层 |
| **分布式锁** | 基于 Redisson 的分布式锁，防止缓存击穿 |
| **TTL 抖动** | 随机化 TTL，避免缓存雪崩 |
| **提前过期** | 异步提前刷新热 key，保证缓存命中率 |
| **空值缓存** | 缓存空值，防止穿透 |
| **熔断/限流** | CircuitBreaker / RateLimiter 包装器，保护下游 |
| **安全序列化** | 白名单反序列化，防范 Jackson 多态类型攻击 |

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

任一 handler 可设置 `output.skipRemaining=true` 短路后续链路；`PostProcessHandler` 在链结束后回调。

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

基础用法（兼容 Spring 原生 `@Cacheable`）：

```java
@Service
public class UserService {
    @Cacheable(value = "users", key = "#id")
    public User getUserById(Long id) {
        return userRepository.findById(id);
    }
}
```

进阶用法（启用 ResiCache 防护）：

```java
@RedisCacheable(value = "users", key = "#id",
                useBloomFilter = true,        // 布隆过滤防穿透
                cacheNullValues = true,       // 空值缓存
                randomTtl = true,              // TTL 抖动防雪崩
                variance = 0.2,                // 抖动幅度 ±20%
                enableEarlyExpiration = true)  // 热 key 提前刷新
public User getUserById(Long id) {
    return userRepository.findById(id);
}
```

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
```

### 分布式锁

```yaml
resi-cache:
  sync-lock:
    timeout: 3000
    unit: MILLISECONDS
    prefix: "cache:lock:"
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
    # sentinel-nodes: [host1:26379]
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
      - "com.example."
```

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
├── factory/             # OperationFactory + 3 个具体工厂
├── handler/             # AnnotationHandler + 4 个注解处理器
├── evaluator/           # SpelConditionEvaluator（condition/unless）
├── eviction/            # TwoListLRU + TwoListEvictionStrategy（双链表近似 LRU）
├── serialization/       # SecureNullValueDeserializer, TypeSupport（安全反序列化）
├── observability/       # CacheMetricsRecorder, RedisCacheHealthIndicator
├── wrapper/             # CircuitBreakerCacheWrapper, RateLimiterCacheWrapper
├── spi/                 # BloomFilterProvider, LockProvider, RedissonLockProvider
├── event/               # CacheEvictedEvent
└── holder/              # CacheOperationMetadataHolder
```

## 🔧 依赖版本

| 依赖 | 版本 |
|------|------|
| Spring Boot | 3.4.13 |
| Java | 17+ |
| Redisson | 3.27.0 |
| Caffeine | 3.1.8 |
| Testcontainers | 1.20.6 |

完整依赖与兼容矩阵见 [COMPATIBILITY.md](COMPATIBILITY.md)、[docs/CODEMAPS/dependencies.md](docs/CODEMAPS/dependencies.md)。

## 📄 License

[MIT License](LICENSE) © 2026 DavidHLP
